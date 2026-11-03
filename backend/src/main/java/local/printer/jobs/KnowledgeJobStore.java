package local.printer.jobs;

import java.util.*;
import local.printer.administration.*;
import local.printer.auth.SessionUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** MySQL owns task progress; broker deliveries are only wake-up hints. */
@Service
public class KnowledgeJobStore {
    public record Claim(String jobId,long documentId,int revision,int cycle,int attempt,
                        String token,String stage,String bindingHash) {}
    public static final class LeaseLost extends RuntimeException {
        public LeaseLost(){super("Knowledge job lease no longer belongs to this execution");}
    }
    private final JdbcTemplate db;
    private final AdminLedger ledger;
    public KnowledgeJobStore(JdbcTemplate db,AdminLedger ledger){this.db=db;this.ledger=ledger;}

    @Transactional
    public String enqueue(String publicationId) {
        // Acceptance is a short administrative mutation. Serialize duplicate INSERTs:
        // INSERT IGNORE alone takes shared duplicate-key locks and can deadlock when
        // concurrent callers then upgrade those locks to own the same job row.
        ledger.lock();
        // The request binding is immutable. Do not lock it ahead of the job row: workers
        // lock job -> request when committing a result, so that order would deadlock.
        var rows=db.queryForList("SELECT * FROM knowledge_publication_requests WHERE id=?",publicationId);
        if(rows.isEmpty())throw new AdminFailure(404,"NOT_FOUND","未找到发布申请");
        var r=rows.get(0);
        if(db.queryForList("SELECT id FROM knowledge_jobs WHERE id=? FOR UPDATE",publicationId).isEmpty())
            db.update("INSERT INTO knowledge_jobs(id,document_id,revision,binding_hash) VALUES(?,?,?,?)",publicationId,r.get("document_id"),r.get("revision"),r.get("binding_hash"));
        var job=row(publicationId);
        if(!"SUCCEEDED".equals(job.get("state")) && !"FAILED".equals(job.get("state"))) queue(job);
        db.update("UPDATE knowledge_publication_requests SET status=(SELECT state FROM knowledge_jobs WHERE id=?) WHERE id=?",publicationId,publicationId);
        return publicationId;
    }

    @Transactional
    public int reconcileRequests() {
        var ids=db.queryForList("""
            SELECT p.id FROM knowledge_publication_requests p LEFT JOIN knowledge_jobs j ON j.id=p.id
            WHERE j.id IS NULL ORDER BY p.created_at LIMIT 100
            """,String.class);
        for(String id:ids)enqueue(id);
        return ids.size();
    }

    @Transactional
    public Optional<Claim> claim(String jobId,String owner,int leaseSeconds) {
        leaseSeconds=lease(leaseSeconds);
        var rows=db.queryForList("SELECT * FROM knowledge_jobs WHERE id=? FOR UPDATE",jobId);
        if(rows.isEmpty())return Optional.empty();
        var r=rows.get(0);
        int eligible=db.queryForObject("""
            SELECT COUNT(*) FROM knowledge_jobs WHERE id=? AND state IN ('QUEUED','RETRY_WAIT')
            AND due_at<=UTC_TIMESTAMP(6) AND attempt<3
            """,Integer.class,jobId);
        if(eligible!=1)return Optional.empty();
        int attempt=number(r,"attempt")+1,cycle=number(r,"cycle");String token=UUID.randomUUID().toString();
        db.update("""
            UPDATE knowledge_jobs SET state='RUNNING',attempt=?,lease_token=?,lease_owner=?,
            lease_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),updated_at=UTC_TIMESTAMP(6) WHERE id=?
            """,attempt,token,owner==null?"worker":owner.substring(0,Math.min(owner.length(),100)),leaseSeconds,jobId);
        db.update("INSERT INTO knowledge_job_attempts(job_id,cycle,attempt,lease_token,state,stage) VALUES(?,?,?,?,'RUNNING',?)",
            jobId,cycle,attempt,token,r.get("stage"));
        db.update("UPDATE knowledge_publication_requests SET status='RUNNING' WHERE id=?",jobId);
        return Optional.of(new Claim(jobId,((Number)r.get("document_id")).longValue(),number(r,"revision"),cycle,attempt,token,(String)r.get("stage"),(String)r.get("binding_hash")));
    }

    /** FOR UPDATE joins a caller's activation transaction, fencing the final short commit. */
    @Transactional
    public void assertLease(Claim claim) { owned(claim); }
    @Transactional
    public void heartbeat(Claim claim,int leaseSeconds) {
        owned(claim);
        db.update("UPDATE knowledge_jobs SET lease_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)) WHERE id=?",lease(leaseSeconds),claim.jobId());
    }
    @Transactional
    public void checkpoint(Claim claim,String stage) {
        owned(claim);stage=code(stage);
        db.update("UPDATE knowledge_jobs SET stage=?,updated_at=UTC_TIMESTAMP(6) WHERE id=?",stage,claim.jobId());
        db.update("UPDATE knowledge_job_attempts SET stage=? WHERE job_id=? AND cycle=? AND attempt=?",stage,claim.jobId(),claim.cycle(),claim.attempt());
    }
    @Transactional
    public void complete(Claim claim) {
        owned(claim);
        db.update("""
            UPDATE knowledge_jobs SET state='SUCCEEDED',stage='PUBLISHED',failure_code=NULL,
            lease_token=NULL,lease_owner=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(6) WHERE id=?
            """,claim.jobId());
        db.update("""
            UPDATE knowledge_job_attempts SET state='SUCCEEDED',stage='PUBLISHED',finished_at=UTC_TIMESTAMP(6)
            WHERE job_id=? AND cycle=? AND attempt=?
            """,claim.jobId(),claim.cycle(),claim.attempt());
        db.update("UPDATE knowledge_publication_requests SET status='SUCCEEDED' WHERE id=?",claim.jobId());
    }
    @Transactional
    public String fail(Claim claim,String failureCode,boolean permanent) {
        var r=owned(claim);return failure(r,code(failureCode),permanent);
    }
    @Transactional
    public int recoverExpired(int limit) {
        bounds(limit);
        var rows=db.queryForList("""
            SELECT * FROM knowledge_jobs WHERE state='RUNNING' AND lease_until<=UTC_TIMESTAMP(6)
            ORDER BY lease_until LIMIT ? FOR UPDATE SKIP LOCKED
            """,limit);
        for(var r:rows)failure(r,"LEASE_EXPIRED_RESULT_UNKNOWN",false);
        return rows.size();
    }
    private String failure(Map<String,Object> r,String code,boolean permanent) {
        String id=(String)r.get("id");int attempt=number(r,"attempt"),cycle=number(r,"cycle");
        boolean exhausted=permanent || attempt>=3;String state=exhausted?"FAILED":"RETRY_WAIT";
        int delay=attempt*2;
        db.update("""
            UPDATE knowledge_jobs SET state=?,failure_code=?,lease_token=NULL,lease_owner=NULL,
            lease_until=NULL,due_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),updated_at=UTC_TIMESTAMP(6) WHERE id=?
            """,state,code,delay,id);
        db.update("""
            UPDATE knowledge_job_attempts SET state='FAILED',failure_code=?,finished_at=UTC_TIMESTAMP(6)
            WHERE job_id=? AND cycle=? AND attempt=?
            """,code,id,cycle,attempt);
        db.update("UPDATE knowledge_publication_requests SET status=? WHERE id=?",state,id);
        if(exhausted)db.update("INSERT IGNORE INTO knowledge_job_dead_letters(job_id,cycle,attempt,failure_code) VALUES(?,?,?,?)",id,cycle,attempt,code);
        else queue(row(id));
        return state;
    }

    public List<Map<String,Object>> outboxDue(int limit) {
        bounds(limit);
        return db.queryForList("""
            SELECT o.id,o.job_id,o.revision,o.stage FROM knowledge_job_outbox o JOIN knowledge_jobs j ON j.id=o.job_id
            WHERE o.state='PENDING' AND o.due_at<=UTC_TIMESTAMP(6) AND j.state IN ('QUEUED','RETRY_WAIT')
            AND j.due_at<=UTC_TIMESTAMP(6) AND o.cycle=j.cycle AND o.next_attempt=j.attempt+1 ORDER BY o.id LIMIT ?
            """,limit);
    }
    public void outboxConfirmed(long id) {
        db.update("UPDATE knowledge_job_outbox SET state='DELIVERED',publish_attempts=publish_attempts+1,delivered_at=UTC_TIMESTAMP(6),failure_code=NULL WHERE id=? AND state='PENDING'",id);
    }
    public void outboxFailed(long id,String failureCode) {
        db.update("UPDATE knowledge_job_outbox SET publish_attempts=publish_attempts+1,failure_code=?,due_at=TIMESTAMPADD(SECOND,2,UTC_TIMESTAMP(6)) WHERE id=? AND state='PENDING'",code(failureCode),id);
    }
    private void queue(Map<String,Object> r) {
        db.update("""
            INSERT IGNORE INTO knowledge_job_outbox(job_id,revision,stage,cycle,next_attempt,due_at)
            VALUES(?,?,?,?,?,?)
            """,r.get("id"),r.get("revision"),r.get("stage"),r.get("cycle"),number(r,"attempt")+1,r.get("due_at"));
    }

    @Transactional
    public JsonNode retry(SessionUser actor,String id,JsonNode body) {
        if(actor==null)throw new org.springframework.security.access.AccessDeniedException("需要管理员身份");
        AdminInput.fields(body,"requestId");
        return ledger.once(actor,"KNOWLEDGE_JOB_RETRY",id,body,()->{
            var r=row(id);if(!"FAILED".equals(r.get("state")))throw AdminFailure.conflict("JOB_NOT_FAILED");
            db.update("""
                UPDATE knowledge_jobs SET state='QUEUED',cycle=cycle+1,attempt=0,failure_code=NULL,
                due_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) WHERE id=?
                """,id);
            queue(row(id));db.update("UPDATE knowledge_publication_requests SET status='QUEUED' WHERE id=?",id);
            return detail(actor,id);
        });
    }
    @Transactional(readOnly=true)
    public JsonNode detail(SessionUser actor,String id) {
        if(actor==null)throw new org.springframework.security.access.AccessDeniedException("需要管理员身份");
        ledger.actor(actor,true);
        var rows=db.queryForList("""
            SELECT id AS jobId,document_id AS documentId,revision,state AS status,stage,cycle,attempt,
            due_at AS dueAt,failure_code AS failureCode,created_at AS createdAt,updated_at AS updatedAt
            FROM knowledge_jobs WHERE id=?
            """,id);
        if(rows.isEmpty())throw new AdminFailure(404,"NOT_FOUND","未找到任务");
        var result=ledger.node(rows.get(0));
        ((tools.jackson.databind.node.ObjectNode)result).set("attempts",ledger.node(db.queryForList("""
            SELECT cycle,attempt,state,stage,failure_code AS failureCode,started_at AS startedAt,finished_at AS finishedAt
            FROM knowledge_job_attempts WHERE job_id=? ORDER BY cycle,attempt
            """,id)));
        ((tools.jackson.databind.node.ObjectNode)result).set("generations",ledger.node(db.queryForList("""
            SELECT g.generation_id AS generationId,g.status,g.manifest_hash AS manifestHash,g.chunk_count AS chunkCount,
            g.vector_verified AS vectorVerified,g.bm25_verified AS bm25Verified,g.published_at AS publishedAt
            FROM knowledge_index_generations g JOIN knowledge_jobs j ON j.document_id=g.document_id AND j.revision=g.revision
            WHERE j.id=? ORDER BY g.created_at
            """,id)));
        ((tools.jackson.databind.node.ObjectNode)result).set("batches",ledger.node(db.queryForList("""
            SELECT b.generation_id AS generationId,b.batch_number AS batchNumber,b.status,b.failure_code AS failureCode
            FROM knowledge_index_batches b JOIN knowledge_index_generations g ON g.generation_id=b.generation_id
            JOIN knowledge_jobs j ON j.document_id=g.document_id AND j.revision=g.revision WHERE j.id=?
            ORDER BY b.generation_id,b.batch_number
            """,id)));
        return result;
    }
    private Map<String,Object> row(String id) {
        var rows=db.queryForList("SELECT * FROM knowledge_jobs WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty())throw new AdminFailure(404,"NOT_FOUND","未找到任务");return rows.get(0);
    }
    private Map<String,Object> owned(Claim c) {
        var r=row(c.jobId());
        if(!"RUNNING".equals(r.get("state")) || !c.token().equals(r.get("lease_token"))
            || number(r,"cycle")!=c.cycle() || number(r,"attempt")!=c.attempt()
            || db.queryForObject("SELECT COUNT(*) FROM knowledge_jobs WHERE id=? AND lease_until>UTC_TIMESTAMP(6)",Integer.class,c.jobId())!=1)throw new LeaseLost();
        return r;
    }
    private static int number(Map<String,Object> r,String key){return ((Number)r.get(key)).intValue();}
    private static int lease(int seconds){if(seconds<1 || seconds>3600)throw new IllegalArgumentException("lease bounds");return seconds;}
    private static void bounds(int limit){if(limit<1 || limit>100)throw new IllegalArgumentException("batch bounds");}
    private static String code(String code){if(code==null || !code.matches("[A-Z][A-Z0-9_]{0,39}"))return "PROCESSING_FAILED";return code;}
}
