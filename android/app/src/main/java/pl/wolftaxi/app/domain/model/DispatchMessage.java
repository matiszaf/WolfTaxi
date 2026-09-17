package pl.wolftaxi.app.domain.model;

public class DispatchMessage {
    public String id = "";
    public String type = "info";
    public String title = "";
    public String body = "";
    public long createdAt = 0;
    public boolean requiresAck = false;
    public boolean voiceRead = true;
    public boolean acknowledged = false;
    public String targetType = "all";
    public String targetId = "";

    public DispatchMessage() {}
    public DispatchMessage(String id, String type, String title, String body, long createdAt, boolean requiresAck) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.body = body;
        this.createdAt = createdAt;
        this.requiresAck = requiresAck;
    }
}
