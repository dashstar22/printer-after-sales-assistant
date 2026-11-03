package local.printer.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static local.printer.knowledge.index.KnowledgeIndexAdapters.*;
import local.printer.knowledge.index.KnowledgeIndexAdapters.Vector;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import local.printer.knowledge.KnowledgePurposePolicy;
import local.printer.knowledge.index.KnowledgeIndexPipeline;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

/** Real MySQL + RabbitMQ + production dispatcher/store/worker/pipeline; index ports are local fixtures. */
class KnowledgeJobDatabaseBrokerIT {
    private static String env(String key){return Objects.requireNonNull(System.getenv(key),key);}
    private static void docker(String command) throws Exception {
        String name=env("PHASE3_RABBIT_CONTAINER");assertTrue(name.matches("phase3-broker-[a-z0-9]+"));
        var p=new ProcessBuilder("docker",command,name).redirectErrorStream(true).start();p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(60,TimeUnit.SECONDS));assertEquals(0,p.exitValue());
    }
    private static void await(java.util.function.BooleanSupplier check) throws Exception {
        for(int i=0;i<100;i++){if(check.getAsBoolean())return;Thread.sleep(100);}fail("expected durable task result before timeout");
    }
    static class LocalPorts implements Embedding,Vector,Bm25 {
        final AtomicInteger calls=new AtomicInteger();volatile boolean permanent;
        public String identity(){return "broker-integration-local-port-v1";}public String configurationId(){return "fixture";}public int dimensions(){return 2;}
        public List<List<Double>> embed(List<String> values){calls.incrementAndGet();return values.stream().map(x->List.of(1.0,2.0)).toList();}
        public void write(Generation g,List<Chunk> chunks,List<List<Double>> values){}
        public void write(Generation g){if(permanent)throw new KnowledgeIndexPipeline.IndexFailure("PERMANENT_FIXTURE_FAILURE",true,false);}
        public Receipt verify(Generation g){return new Receipt(g.id(),g.configurationHash(),g.manifestHash(),g.chunks().stream().map(Chunk::id).toList());}
    }
    private static String fixture(StoreDatabaseTest f) {
        String job=f.request();long id=f.db.queryForObject("SELECT document_id FROM knowledge_publication_requests WHERE id=?",Long.class,job);
        var metadata=Map.of("version","v1","title","本地集成合成夹具","sourceType","enterprise_faq","isSimulated",false,"modelScope","GENERAL","models",List.of(),
                "useCondition","test","distributionAllowed",false,"source",Map.of("checkedOn","2026-11-01","description","local synthetic fixture"),
                "processingPermissions",Map.of("embedding",Map.of("allowed",true,"configurationId","fixture"),"rerank",Map.of("allowed",true,"configurationId","fixture"),"chat",Map.of("allowed",true,"configurationId","fixture")));
        f.db.update("UPDATE knowledge_revisions SET metadata_json=? WHERE document_id=?",JsonMapper.builder().build().writeValueAsString(metadata),id);
        f.db.update("INSERT INTO knowledge_chunks(document_id,revision,chunk_index,stable_chunk_id,content,section,layout_version) VALUES(?,1,0,?,'合成知识不得作为维修说明','合成','knowledge-layout-v2')",id,KnowledgeIndexPipeline.digest(job));
        for(String purpose:List.of("embedding","rerank","chat"))f.db.update("INSERT INTO knowledge_permissions(document_id,revision,purpose,allowed,configuration_id) VALUES(?,1,?,TRUE,'fixture')",id,purpose);
        return job;
    }
    @Test void completeDurableChainDuplicateFailureAndBrokerOutage() throws Exception {
        String url=env("PHASE3_DB_URL");assertTrue(url.contains("printer_p3_broker_"));
        Flyway.configure().dataSource(url,env("PHASE3_DB_USER"),env("PHASE3_DB_PASSWORD")).cleanDisabled(true).load().migrate();
        var f=new StoreDatabaseTest();f.connect();
        var tm=new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.db.getDataSource());
        var permissions=new KnowledgePurposePolicy(new MockEnvironment().withProperty("app.knowledge.processing-configurations","embedding:fixture,rerank:fixture,chat:fixture"));
        var ports=new LocalPorts();var pipeline=new KnowledgeIndexPipeline(f.db,JsonMapper.builder().build(),tm,permissions,List.of(ports),List.of(ports),List.of(ports));
        var worker=new KnowledgeJobWorker(f.store,pipeline,120);
        var connection=new CachingConnectionFactory("127.0.0.1",Integer.parseInt(env("PHASE3_RABBIT_PORT")));
        connection.setUsername(env("PHASE3_RABBIT_USER"));connection.setPassword(env("PHASE3_RABBIT_PASSWORD"));connection.setConnectionTimeout(1000);
        String prefix="p3.db."+UUID.randomUUID(),queue=prefix+".work",deadQueue=prefix+".dead",exchange=prefix+".exchange",dlx=prefix+".dlx";
        var dispatch=new KnowledgeJobMessaging(f.store,connection,exchange,"publish");var admin=new RabbitAdmin(connection);
        for(var d:dispatch.knowledgeJobTopology(queue,dlx,deadQueue).getDeclarables()){if(d instanceof Exchange e)admin.declareExchange(e);if(d instanceof org.springframework.amqp.core.Queue q)admin.declareQueue(q);}
        for(var d:dispatch.knowledgeJobTopology(queue,dlx,deadQueue).getDeclarables())if(d instanceof Binding b)admin.declareBinding(b);
        var listener=dispatch.knowledgeJobListener(connection,worker,queue);listener.start();
        try {
            String success=fixture(f);f.tx.executeWithoutResult(s->f.store.enqueue(success));dispatch.dispatch();
            await(()->"SUCCEEDED".equals(f.db.queryForObject("SELECT state FROM knowledge_jobs WHERE id=?",String.class,success)));
            assertEquals("DELIVERED",f.db.queryForObject("SELECT state FROM knowledge_job_outbox WHERE job_id=?",String.class,success));
            assertEquals("PUBLISHED",f.db.queryForObject("SELECT stage FROM knowledge_job_attempts WHERE job_id=?",String.class,success));
            assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM knowledge_active_generations a JOIN knowledge_index_generations g ON g.generation_id=a.generation_id JOIN knowledge_jobs j ON j.document_id=g.document_id WHERE j.id=?",Integer.class,success));
            await(()->admin.getQueueInfo(queue).getMessageCount()==0);
            int calls=ports.calls.get();var rabbit=new RabbitTemplate(connection);
            var props=new MessageProperties();props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);props.setContentType("application/json");
            rabbit.send(exchange,"publish",new Message(("{\"jobId\":\""+success+"\",\"revision\":1,\"stage\":\"INDEXING\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8),props));
            Thread.sleep(300);assertEquals(1,f.count("knowledge_job_attempts",success));assertEquals(calls,ports.calls.get());

            ports.permanent=true;String failed=fixture(f);f.store.enqueue(failed);dispatch.dispatch();
            await(()->"FAILED".equals(f.db.queryForObject("SELECT state FROM knowledge_jobs WHERE id=?",String.class,failed)));
            assertEquals(1,f.count("knowledge_job_dead_letters",failed));assertEquals(1,f.count("knowledge_job_attempts",failed));
            var dead=rabbit.receive(deadQueue,5000);assertNotNull(dead);assertTrue(new String(dead.getBody(),java.nio.charset.StandardCharsets.UTF_8).contains(failed));

            ports.permanent=false;listener.stop();docker("stop");connection.destroy();String recovered=fixture(f);f.store.enqueue(recovered);dispatch.dispatch();
            assertEquals("PENDING",f.db.queryForObject("SELECT state FROM knowledge_job_outbox WHERE job_id=?",String.class,recovered));
            assertEquals("BROKER_CONFIRM_UNKNOWN",f.db.queryForObject("SELECT failure_code FROM knowledge_job_outbox WHERE job_id=?",String.class,recovered));
            docker("start");
            for(int i=0;i<60;i++)try{connection.createConnection().createChannel(false).close();break;}catch(Exception e){Thread.sleep(500);}
            listener.start();f.db.update("UPDATE knowledge_job_outbox SET due_at=UTC_TIMESTAMP(6) WHERE job_id=?",recovered);dispatch.dispatch();
            await(()->"SUCCEEDED".equals(f.db.queryForObject("SELECT state FROM knowledge_jobs WHERE id=?",String.class,recovered)));
            assertEquals(1,f.count("knowledge_job_attempts",recovered));assertEquals("DELIVERED",f.db.queryForObject("SELECT state FROM knowledge_job_outbox WHERE job_id=?",String.class,recovered));
        }finally {listener.stop();admin.deleteQueue(queue);admin.deleteQueue(deadQueue);admin.deleteExchange(exchange);admin.deleteExchange(dlx);connection.destroy();}
    }
}
