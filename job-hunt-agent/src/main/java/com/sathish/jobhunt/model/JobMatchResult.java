package com.sathish.jobhunt.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobMatchResult {

    @JsonProperty("matchScore")
    private int matchScore;

    @JsonProperty("matchedSkills")
    private List<String> matchedSkills;

    @JsonProperty("missingSkills")
    private List<String> missingSkills;

    @JsonProperty("priority")
    private String priority;   // HIGH / MEDIUM / LOW / SKIP

    @JsonProperty("reasoning")
    private String reasoning;

    @JsonProperty("suggestedTitle")
    private String suggestedTitle;

    @JsonProperty("estimatedCtcRange")
    private String estimatedCtcRange;

    @JsonProperty("keywordsToHighlight")
    private List<String> keywordsToHighlight;

    @JsonProperty("recruiterTips")
    private String recruiterTips;

    @JsonProperty("disqualified")
    private boolean disqualified;

    @JsonProperty("disqualifyReason")
    private String disqualifyReason;
}
