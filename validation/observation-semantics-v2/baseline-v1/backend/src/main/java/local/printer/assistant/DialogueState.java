package local.printer.assistant;

import java.time.LocalDateTime;
import java.util.*;

/** Server-owned literal field collection; never populated from a model response. */
public record DialogueState(String conversationId,long customerId,long orderId,String phase,
        String symptom,String extra,String contact,String contactMethod,String pendingField,
        String authorizedTurn,LocalDateTime expiresAt,long revision,String lastTurn) {
    static final List<String> FIELDS=List.of("symptom","extra","contact","contactMethod");
    List<String> values(){return Arrays.asList(symptom,extra,contact,contactMethod);}
    List<String> missing(){
        var result=new ArrayList<String>();var values=values();
        for(int i=0;i<FIELDS.size();i++)if(values.get(i)==null||values.get(i).isBlank())result.add(FIELDS.get(i));
        return List.copyOf(result);
    }
    boolean active(LocalDateTime now){return Set.of("COLLECTING","PAUSED").contains(phase)&&now.isBefore(expiresAt);}
    DialogueState next(String phase,List<String> values,String turn){
        String pending=null;
        for(int i=0;i<4;i++)if(values.get(i)==null||values.get(i).isBlank()){pending=FIELDS.get(i);break;}
        return new DialogueState(conversationId,customerId,orderId,phase,values.get(0),values.get(1),values.get(2),values.get(3),
            pending,authorizedTurn,expiresAt,revision+1,turn);
    }
    @Override public String toString(){return "DialogueState[phase="+phase+", revision="+revision+"]";}
}
