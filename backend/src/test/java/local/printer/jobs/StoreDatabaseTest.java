package local.printer.jobs;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import local.printer.administration.*;
import local.printer.auth.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Real isolated MySQL only; creates unique fixtures and never cleans existing rows. */
class StoreDatabaseTest {
    JdbcTemplate db;KnowledgeJobStore store;TransactionTemplate tx;AdminLedger ledger;SessionUser actor;long uid;
    @BeforeEach void connect() {
        String url=System.getenv("PHASE3_DB_URL");Assumptions.assumeTrue(url!=null,"PHASE3_DB_URL required for isolated DB tests");
        var ds=new DriverManagerDataSource(url,System.getenv("PHASE3_DB_USER"),System.getenv("PHASE3_DB_PASSWORD"));
        db=new JdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        ledger=new AdminLedger(db,JsonMapper.builder().build());
        var proxy=new TransactionProxyFactoryBean();proxy.setProxyTargetClass(true);proxy.setTarget(new KnowledgeJobStore(db,ledger));proxy.setTransactionManager(manager);
        var attrs=new Properties();attrs.setProperty("*","PROPAGATION_REQUIRED");proxy.setTransactionAttributes(attrs);proxy.afterPropertiesSet();store=(KnowledgeJobStore)proxy.getObject();
        String username="p3-job-"+UUID.randomUUID();
        db.update("INSERT INTO users(username,password_hash,role,email,display_email,email_verified_at) VALUES(?,'not-a-login-hash','ADMIN',?,?,UTC_TIMESTAMP(6))",username,username+"@example.invalid",username+"@example.invalid");
        uid=db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,username);
        actor=new SessionUser(new AuthUserMapper.Account(uid,username,"not-a-login-hash","ADMIN"));
    }
    String request() {
        String key="job-test-"+UUID.randomUUID();String hash="a".repeat(64);
        db.update("""
            INSERT INTO knowledge_documents(document_key,version,title,source_type,self_authored,is_simulated,model_scope,models_json,
            checked_on,use_condition,distribution_allowed,external_upload_allowed,local_file,provenance_json)
            VALUES(?,'v1','合成任务夹具','enterprise_faq',TRUE,FALSE,'GENERAL','[]',CURRENT_DATE(),'fixture',FALSE,FALSE,'private','{}')
            """,key);
        long id=db.queryForObject("SELECT id FROM knowledge_documents WHERE document_key=?",Long.class,key);
        db.update("""
            INSERT INTO knowledge_revisions(document_id,revision,storage_key,file_name,metadata_json,content_hash,layout_hash,
            config_hash,binding_hash,layout_version,extracted_text,chunk_count,created_by)
            VALUES(?,1,?,'fixture.md','{}',?,?,?,?,'knowledge-layout-v2','合成资料',1,?)
            """,id,UUID.randomUUID().toString(),hash,hash,hash,hash,uid);
        String job=UUID.randomUUID().toString();
        db.update("INSERT INTO knowledge_publication_requests(id,document_id,revision,binding_hash,actor_id) VALUES(?,?,1,?,?)",job,id,hash,uid);return job;
    }
    String enqueue(){String id=request();store.enqueue(id);return id;}
    int count(String table,String job){return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+(table.equals("knowledge_jobs")?"id":"job_id")+"=?",Integer.class,job);}
    KnowledgeJobStore.Claim claim(String job){return store.claim(job,"test",30).orElseThrow();}
    void due(String job){db.update("UPDATE knowledge_jobs SET due_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",job);}

    @Test void transactionRollbackDoesNotLeaveTaskOrOutbox() {
        String job=request();tx.executeWithoutResult(s->{store.enqueue(job);s.setRollbackOnly();});
        assertEquals(0,count("knowledge_jobs",job));assertEquals(0,count("knowledge_job_outbox",job));
        store.enqueue(job);assertEquals(1,count("knowledge_jobs",job));assertEquals(1,count("knowledge_job_outbox",job));
    }
    @Test void committedIntentRecoveredAfterCrashAndConcurrentEnqueueIsUnique()throws Exception {
        String job=request();assertEquals(0,count("knowledge_jobs",job));store.reconcileRequests();assertEquals(1,count("knowledge_jobs",job));
        var pool=Executors.newFixedThreadPool(4);
        try {var futures=new ArrayList<Future<String>>();for(int i=0;i<8;i++)futures.add(pool.submit(()->store.enqueue(job)));for(var f:futures)assertEquals(job,f.get(20,TimeUnit.SECONDS));}
        finally {pool.shutdownNow();}
        assertEquals(1,count("knowledge_jobs",job));assertEquals(1,count("knowledge_job_outbox",job));
    }
    @Test void duplicateDeliveriesBeforeAndAfterPersistedResultCannotRepeatBusinessAttempt() {
        String job=enqueue();var c=claim(job);assertTrue(store.claim(job,"duplicate",30).isEmpty());
        store.checkpoint(c,"VERIFYING");assertEquals("VERIFYING",db.queryForObject("SELECT stage FROM knowledge_jobs WHERE id=?",String.class,job));
        store.complete(c);assertTrue(store.claim(job,"duplicate-after-ack-loss",30).isEmpty());assertEquals(1,count("knowledge_job_attempts",job));
        assertThrows(KnowledgeJobStore.LeaseLost.class,()->store.complete(c));
    }
    @Test void expiredLeaseIsFencedAndCheckpointRetainedForNextAttempt() {
        String job=enqueue();var stale=claim(job);store.checkpoint(stale,"VECTOR_VERIFIED");
        db.update("UPDATE knowledge_jobs SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",job);
        assertThrows(KnowledgeJobStore.LeaseLost.class,()->store.assertLease(stale));
        assertTrue(store.recoverExpired(100)>=1);due(job);var next=claim(job);
        assertEquals(2,next.attempt());assertEquals("VECTOR_VERIFIED",next.stage());
        assertThrows(KnowledgeJobStore.LeaseLost.class,()->store.fail(stale,"TRANSIENT_FAILURE",false));
        store.complete(next);assertEquals(2,count("knowledge_job_attempts",job));
    }
    @Test void transientAttemptsStopAtThreeAndAdminRetryPreservesHistoryAndAudit() {
        String job=enqueue();for(int n=1;n<=3;n++){var c=claim(job);assertEquals(n,c.attempt());assertEquals(n==3?"FAILED":"RETRY_WAIT",store.fail(c,"BROKER_OR_INDEX_UNAVAILABLE",false));due(job);}
        assertTrue(store.claim(job,"automatic-fourth",30).isEmpty());assertEquals(3,count("knowledge_job_attempts",job));assertEquals(1,count("knowledge_job_dead_letters",job));
        String request=UUID.randomUUID().toString();var body=ledger.json().createObjectNode().put("requestId",request);
        assertEquals("QUEUED",store.retry(actor,job,body).path("status").asString());
        store.retry(actor,job,body);var c=claim(job);assertEquals(2,c.cycle());assertEquals(1,c.attempt());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM administration_audit WHERE actor_id=? AND action='KNOWLEDGE_JOB_RETRY'",Integer.class,uid));
        assertEquals(4,count("knowledge_job_attempts",job));assertEquals(1,count("knowledge_job_dead_letters",job));
    }
    @Test void permanentFailureStopsImmediatelyAndFailedPublisherRemainsPending() {
        String job=enqueue();var outbox=store.outboxDue(100).stream().filter(r->job.equals(r.get("job_id"))).findFirst().orElseThrow();
        long oid=((Number)outbox.get("id")).longValue();store.outboxFailed(oid,"UNCONFIRMED");
        assertEquals("PENDING",db.queryForObject("SELECT state FROM knowledge_job_outbox WHERE id=?",String.class,oid));
        store.outboxConfirmed(oid);store.outboxConfirmed(oid);
        assertEquals("DELIVERED",db.queryForObject("SELECT state FROM knowledge_job_outbox WHERE id=?",String.class,oid));
        var c=claim(job);assertEquals("FAILED",store.fail(c,"PURPOSE_NOT_ALLOWED",true));assertTrue(store.claim(job,"loop",30).isEmpty());
        assertEquals(1,count("knowledge_job_attempts",job));assertEquals(1,count("knowledge_job_dead_letters",job));
    }
}
