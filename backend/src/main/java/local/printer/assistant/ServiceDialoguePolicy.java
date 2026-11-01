package local.printer.assistant;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Fixed service guidance, never a knowledge answer or authorization to write. */
final class ServiceDialoguePolicy {
    private ServiceDialoguePolicy() {}

    static EvidenceAnswerPolicy.Answer localReply(String text) {
        if (text == null) return null;
        // Match a whole, standalone message. Mixed requests must still reach the model/tools.
        String value = text.strip().toLowerCase(Locale.ROOT).replaceFirst("[。！？!?\\s]+$", "");
        if (Set.of("你好", "您好", "你好呀", "嗨", "hi", "hello", "在吗", "有人吗", "早上好", "下午好", "晚上好").contains(value))
            return guidance("你好，我是纸序售后助手。设备遇到了什么问题？告诉我故障现象，我可以帮你查资料、整理报修信息，也可以引导你查看工单进度。");
        if (Set.of("谢谢", "谢谢你", "谢谢了", "好的谢谢", "好的，谢谢", "多谢", "thanks", "thank you").contains(value))
            return guidance("不客气。设备还有其他情况，随时告诉我；已经提交的报修可以在“我的工单”查看进度。");
        if (Set.of("你能做什么", "你有什么功能", "怎么使用", "你是谁").contains(value)) return scope();
        if (value.matches("(?:今天|明天|现在)?(?:的)?天气(?:如何|怎么样|怎样|好吗|预报|情况)?")
                || value.matches("(?:今天|明天)?会下雨吗"))
            return guidance("我目前不能查询实时天气，出行前请查看天气应用。设备售后的问题可以继续问我，比如故障现象、报修要求或工单进度。");
        return null;
    }

    static EvidenceAnswerPolicy.Answer scope() {
        return guidance("我可以帮你查询设备售后资料、整理报修信息和查看工单入口。请告诉我设备遇到的情况；提交报修前，你可以在页面核对并确认内容。");
    }

    /** A retrieval requirement only. This classifier can never authorize a write. */
    static boolean requiresKnowledge(String text) {
        if (text == null || AssistantConversationService.creation(text) || AssistantConversationService.edit(text) != null)
            return false;
        // Field-value continuations and ticket navigation keep their existing workflow contracts.
        if (text.matches("(?s)^(?:故障描述|故障|现场补充信息|现场|联系人|联系方式)[：:].*")) return false;
        if (text.matches("(?s).*(?:工单|报修).*(?:进度|处理到哪|关闭结果|查看记录).*")) return false;
        return text.matches("(?s).*(?:报修|草稿|故障描述|现场补充|现场信息|联系人|联系方式|联系方法).*")
            && text.matches("(?s).*(?:什么|哪些|哪几|怎么|怎样|如何|能否|是否|可不可以|可否|必须|一定要|上限|最多|长度|字数|限制|要求|格式|需要|准备).*" );
    }

    static EvidenceAnswerPolicy.Answer noEvidence(String currentText) {
        if (currentText != null && currentText.contains("卡纸"))
            return new EvidenceAnswerPolicy.Answer("NO_EVIDENCE",
                "暂时没有找到这台设备适用的卡纸处理资料，还不能确定原因或给出拆机步骤。\n\n先告诉我：纸大约卡在进纸处、机身内部，还是出纸口？屏幕有没有错误码或提示？\n如果已经尝试过处理，也把做过的操作和结果告诉我。我可以继续帮你整理报修信息。", List.of());
        return EvidenceAnswerPolicy.noEvidence();
    }

    private static EvidenceAnswerPolicy.Answer guidance(String text) {
        return new EvidenceAnswerPolicy.Answer("ANSWERED", text, List.of());
    }
}
