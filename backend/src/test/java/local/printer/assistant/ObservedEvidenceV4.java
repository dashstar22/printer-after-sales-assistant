package local.printer.assistant;

import java.util.*;

/** 测试侧独立观测与逐步落盘；不参与产品运行。 */
final class ObservedEvidenceV4 {
    @FunctionalInterface interface Read { Object get() throws Exception; }
    @FunctionalInterface interface Write { void save(Map<String,Object> data) throws Exception; }
    static Map<String,Object> collect(Map<String,Object> data, Map<String,Read> steps, Write write) throws Exception {
        write.save(data);
        for(var step:steps.entrySet()) {
            try { data.put(step.getKey(),step.getValue().get()); }
            catch(Exception|AssertionError failure) {
                @SuppressWarnings("unchecked")
                var errors=(Map<String,String>)data.computeIfAbsent("observationErrors",key->new LinkedHashMap<String,String>());
                errors.put(step.getKey(),failure.getClass().getSimpleName());
            }
            write.save(data);
        }
        return data;
    }
}
