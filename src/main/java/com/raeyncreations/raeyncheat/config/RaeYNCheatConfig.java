package com.raeyncreations.raeyncheat.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.raeyncreations.raeyncheat.RaeYNCheat;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class RaeYNCheatConfig {
    
    // Checksum violation settings
    public volatile boolean enablePunishmentSystem = true;
    public List<Integer> punishmentSteps = java.util.Collections.synchronizedList(createDefaultPunishmentSteps());
    
    // Passkey violation settings
    public volatile boolean enablePasskeyPunishmentSystem = true;
    public List<Integer> passkeyPunishmentSteps = java.util.Collections.synchronizedList(createDefaultPasskeyPunishmentSteps());
    
    // Sensitivity settings for false positive detection
    public volatile int sensitivityThresholdLow = 2;  // 1-2 files = might be intentional check
    public volatile int sensitivityThresholdHigh = 10; // 10+ files = might be accidental
    public volatile boolean enableSensitivityChecks = true;
    
    // Constants
    private static final int INVALID_STEP_INDEX = -999;
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    public static RaeYNCheatConfig load(Path configPath) {
        File configFile = configPath.toFile();
        if (configFile.exists()) {
            try (Reader reader = new FileReader(configFile)) {
                RaeYNCheatConfig config = GSON.fromJson(reader, RaeYNCheatConfig.class);
                config.validate();
                return config;
            } catch (Exception e) {
                RaeYNCheat.LOGGER.error("Error loading config, using defaults: {}", e.getMessage());
            }
        }
        
        RaeYNCheatConfig config = new RaeYNCheatConfig();
        config.save(configPath);
        return config;
    }
    
    public void save(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = new FileWriter(configPath.toFile())) {
                GSON.toJson(this, writer);
            }
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error saving config: {}", e.getMessage());
        }
    }
    
    /**
     * Validate configuration values
     */
    private void validate() {
        // Validate checksum punishment steps
        validatePunishmentSteps(punishmentSteps, "punishment");
        
        // Validate passkey punishment steps
        validatePunishmentSteps(passkeyPunishmentSteps, "passkey punishment");
        
        // Validate sensitivity thresholds
        if (sensitivityThresholdLow < 0) {
            RaeYNCheat.LOGGER.warn("Invalid sensitivityThresholdLow: {}. Must be >= 0. Using default: 2", sensitivityThresholdLow);
            sensitivityThresholdLow = 2;
        }
        if (sensitivityThresholdHigh < sensitivityThresholdLow) {
            RaeYNCheat.LOGGER.warn("Invalid sensitivityThresholdHigh: {}. Must be >= sensitivityThresholdLow. Using default: 10", sensitivityThresholdHigh);
            sensitivityThresholdHigh = 10;
        }
    }
    
    /**
     * Validate a list of punishment steps
     * Note: No need for synchronized since the list is already thread-safe
     */
    private void validatePunishmentSteps(List<Integer> steps, String type) {
        for (int i = 0; i < steps.size(); i++) {
            int step = steps.get(i);
            // Only -1, 0, and positive integers are valid
            if (step < -1) {
                RaeYNCheat.LOGGER.warn("Invalid {} step at index {}: {}. Must be -1, 0, or positive integer. Using 0.", type, i, step);
                steps.set(i, 0);
            }
        }
        
        // Limit to 30 steps
        if (steps.size() > 30) {
            RaeYNCheat.LOGGER.warn("{} steps exceed maximum of 30. Truncating to 30 steps.", type);
            while (steps.size() > 30) {
                steps.remove(steps.size() - 1);
            }
        }
    }
    
    /**
     * Create default punishment steps
     * Progressive: 60s, 300s (5min), 600s (10min), 1800s (30min), 3600s (1hr), 
     * 7200s (2hr), 14400s (4hr), 28800s (8hr), 86400s (24hr), -1 (permanent)
     */
    private static List<Integer> createDefaultPunishmentSteps() {
        List<Integer> steps = new ArrayList<>();
        steps.add(60);      // 1 minute
        steps.add(300);     // 5 minutes
        steps.add(600);     // 10 minutes
        steps.add(1800);    // 30 minutes
        steps.add(3600);    // 1 hour
        steps.add(7200);    // 2 hours
        steps.add(14400);   // 4 hours
        steps.add(28800);   // 8 hours
        steps.add(86400);   // 24 hours
        steps.add(-1);      // Permanent ban
        return steps;
    }
    
    /**
     * Create default passkey punishment steps
     * More aggressive than checksum violations since passkey failures are more suspicious
     */
    private static List<Integer> createDefaultPasskeyPunishmentSteps() {
        List<Integer> steps = new ArrayList<>();
        steps.add(300);     // 5 minutes
        steps.add(1800);    // 30 minutes
        steps.add(7200);    // 2 hours
        steps.add(86400);   // 24 hours
        steps.add(-1);      // Permanent ban
        return steps;
    }
    
    public int getPunishmentDuration(int violationCount) {
        return getPunishmentDuration(violationCount, punishmentSteps);
    }
    
    public int getPasskeyPunishmentDuration(int violationCount) {
        return getPunishmentDuration(violationCount, passkeyPunishmentSteps);
    }
    
    private int getPunishmentDuration(int violationCount, List<Integer> steps) {
        if (violationCount <= 0 || steps.isEmpty()) {
            return 0;
        }
        
        // Use the last step if violations exceed step count
        int index = Math.min(violationCount - 1, steps.size() - 1);
        return steps.get(index);
    }
    
    /**
     * Set a checksum punishment step at the given index
     * @param index The step index (0-based, will create new steps if needed)
     * @param duration The duration in seconds (-1 for permanent, 0 for warning, positive for temp ban)
     * @return true if successful, false if invalid
     */
    public boolean setChecksumPunishmentStep(int index, int duration) {
        return setPunishmentStep(punishmentSteps, index, duration, "checksum");
    }
    
    /**
     * Set a passkey punishment step at the given index
     * @param index The step index (0-based, will create new steps if needed)
     * @param duration The duration in seconds (-1 for permanent, 0 for warning, positive for temp ban)
     * @return true if successful, false if invalid
     */
    public boolean setPasskeyPunishmentStep(int index, int duration) {
        return setPunishmentStep(passkeyPunishmentSteps, index, duration, "passkey");
    }
    
    /**
     * Internal method to set a punishment step
     * @param steps The list of punishment steps to modify (already thread-safe)
     * @param index The 0-based index of the step
     * @param duration The duration in seconds
     * @param type The type name for error messages
     * @return true if successful, false if invalid
     * 
     * Note: If the index is beyond the current list size, the list will be extended
     * with 0 (WARNING) values up to that index. This allows gradual configuration
     * of punishment steps without requiring all steps to be defined at once.
     */
    private boolean setPunishmentStep(List<Integer> steps, int index, int duration, String type) {
        // Validate index (0-based, max 29 for 30 total steps)
        if (index < 0 || index >= 30) {
            RaeYNCheat.LOGGER.error("Invalid {} step index: {}. Must be 0-29.", type, index);
            return false;
        }
        
        // Validate duration
        if (duration < -1) {
            RaeYNCheat.LOGGER.error("Invalid {} duration: {}. Must be -1, 0, or positive integer.", type, duration);
            return false;
        }
        
        // Extend list with WARNING (0) values if necessary
        while (steps.size() <= index) {
            steps.add(0);
        }
        
        // Set the step
        steps.set(index, duration);
        return true;
    }
    
    /**
     * Get the current checksum punishment steps as a readable string
     */
    public String getChecksumPunishmentStepsString() {
        return getPunishmentStepsString(punishmentSteps);
    }
    
    /**
     * Get the current passkey punishment steps as a readable string
     */
    public String getPasskeyPunishmentStepsString() {
        return getPunishmentStepsString(passkeyPunishmentSteps);
    }
    
    /**
     * Internal method to format punishment steps
     */
    private String getPunishmentStepsString(List<Integer> steps) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) sb.append(", ");
            int duration = steps.get(i);
            sb.append("[").append(i).append("]: ");
            if (duration == -1) {
                sb.append("PERMANENT");
            } else if (duration == 0) {
                sb.append("WARNING");
            } else {
                sb.append(duration).append("s");
            }
        }
        return sb.toString();
    }
    
    /**
     * Get a specific checksum punishment step value
     */
    public int getChecksumPunishmentStep(int index) {
        if (index < 0 || index >= punishmentSteps.size()) {
            return INVALID_STEP_INDEX;
        }
        return punishmentSteps.get(index);
    }
    
    /**
     * Get a specific passkey punishment step value
     */
    public int getPasskeyPunishmentStep(int index) {
        if (index < 0 || index >= passkeyPunishmentSteps.size()) {
            return INVALID_STEP_INDEX;
        }
        return passkeyPunishmentSteps.get(index);
    }
    
    /**
     * Check if a step index is the invalid marker
     */
    public static boolean isInvalidStepIndex(int value) {
        return value == INVALID_STEP_INDEX;
    }
}
