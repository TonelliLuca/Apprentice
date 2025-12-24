package agent.memory;

import java.util.ArrayList;
import java.util.List;

public class ToolManual {
    private String toolName;
    private String shortDescription;
    private String fullContent;
    private List<String> userNotes;

    public ToolManual(String toolName, String shortDescription, String fullContent) {
        this.toolName = toolName;
        this.shortDescription = shortDescription;
        this.fullContent = fullContent;
        this.userNotes = new ArrayList<>();
    }

    public String toCatalogEntry() {
        return String.format("- TOOL: %s | DESC: %s", toolName, shortDescription);
    }

    public String toFullManual() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== MANUAL: ").append(toolName).append(" ===\n");
        sb.append("DESCRIPTION: ").append(shortDescription).append("\n");
        sb.append("INSTRUCTIONS:\n").append(fullContent).append("\n");

        if (!userNotes.isEmpty()) {
            sb.append("⚠️ USER NOTES & TIPS:\n");
            for (String note : userNotes) {
                sb.append(" - ").append(note).append("\n");
            }
        }
        return sb.toString();
    }

    public void addNote(String note) {
        this.userNotes.add(note);
    }

    public String getToolName() { return toolName; }
}