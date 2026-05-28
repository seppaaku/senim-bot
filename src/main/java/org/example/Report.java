package org.example;

public class Report {
    public enum Type {
        FRAUD("Алаяқтық / Мошенничество"),
        SUSPICIOUS_ORG("Күдікті ұйым / Подозрительная организация"),
        OTHER("Басқа / Другое");

        private final String label;
        Type(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    private final long userId;
    private final String username;
    private Type type;
    private String orgName;
    private String description;
    private String contactInfo;

    public Report(long userId, String username) {
        this.userId = userId;
        this.username = username;
    }

    // Getters & setters
    public long getUserId() { return userId; }
    public String getUsername() { return username; }
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public String getOrgName() { return orgName; }
    public void setOrgName(String orgName) { this.orgName = orgName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getContactInfo() { return contactInfo; }
    public void setContactInfo(String contactInfo) { this.contactInfo = contactInfo; }

    public String toAdminMessage() {
        return String.format("""
                🚨 *Жаңа шағым / Новое обращение*
                
                👤 Пайдаланушы: @%s (ID: %d)
                📋 Түрі: %s
                🏢 Ұйым/Атауы: %s
                📝 Сипаттама: %s
                📞 Байланыс: %s
                """,
                username != null ? username : "белгісіз",
                userId,
                type != null ? type.getLabel() : "—",
                orgName != null ? orgName : "—",
                description != null ? description : "—",
                contactInfo != null ? contactInfo : "—"
        );
    }
}