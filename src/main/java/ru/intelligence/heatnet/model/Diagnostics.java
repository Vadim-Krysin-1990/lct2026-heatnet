package ru.intelligence.heatnet.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Диагностика входных данных и расчёта: ошибки, предупреждения, восстановленные значения, статистика. */
public class Diagnostics {

    public enum Level { ERROR, WARNING, INFO }

    public static class Message {
        public Level level;
        public String code;
        public String text;
        public String objectId;

        public Message(Level level, String code, String text, String objectId) {
            this.level = level; this.code = code; this.text = text; this.objectId = objectId;
        }
    }

    public final List<Message> messages = new ArrayList<>();
    public final Map<String, Object> stats = new LinkedHashMap<>();

    public void error(String code, String text, String objectId) { messages.add(new Message(Level.ERROR, code, text, objectId)); }
    public void warn(String code, String text, String objectId) { messages.add(new Message(Level.WARNING, code, text, objectId)); }
    public void info(String code, String text, String objectId) { messages.add(new Message(Level.INFO, code, text, objectId)); }

    public boolean hasErrors() {
        return messages.stream().anyMatch(m -> m.level == Level.ERROR);
    }

    public long count(Level level) {
        return messages.stream().filter(m -> m.level == level).count();
    }
}
