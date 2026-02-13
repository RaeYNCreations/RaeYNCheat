package com.raeyncreations.raeyncheat.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class RaeYNCheatConfig {
    
    // Checksum violation settings
    public boolean enablePunishmentSystem = true;
    public List<Integer> punishmentSteps = createDefaultPunishmentSteps();
    
    // Passkey violation settings
    public boolean enablePasskeyPunishmentSystem = true;
    public List<Integer> passkeyPunishmentSteps = createDefaultPasskeyPunishmentSteps();
    
    // Sensitivity settings for false positive detection
    public int sensitivityThresholdLow = 2;  // 1-2 files = might be intentional check
    public int sensitivityThresholdHigh = 10; // 10+ files = might be accidental
    public boolean enableSensitivityChecks = true;
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    public static RaeYNCheatConfig load(Path configPath) {
        File configFile = configPath.toFile();
        if (configFile.exists()) {
            try (Reader reader = new FileReader(configFile)) {
                RaeYNCheatConfig config = GSON.fromJson(reader, RaeYNCheatConfig.class);
                config.validate();
                return config;
            } catch (Exception e) {
                System.err.println("Error loading config, using defaults: " + e.getMessage());
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
            System.err.println("Error saving config: " + e.getMessage());
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
            System.err.println("Invalid sensitivityThresholdLow: " + sensitivityThresholdLow + ". Must be >= 0.");
            sensitivityThresholdLow = 2;
        }
        if (sensitivityThresholdHigh < sensitivityThresholdLow) {
            System.err.println("Invalid sensitivityThresholdHigh: " + sensitivityThresholdHigh + ". Must be >= sensitivityThresholdLow.");
            sensitivityThresholdHigh = 10;
        }
    }
    
    /**
     * Validate a list of punishment steps
     */
    private void validatePunishmentSteps(List<Integer> steps, String type) {
        for (int i = 0; i < steps.size(); i++) {
            int step = steps.get(i);
            if (step < -1) {
                System.err.println("Invalid " + type + " step at index " + i + ": " + step + ". Must be -1, 0, or positive integer.");
                steps.set(i, 0);
            }
            // Only -1 and 0 are allowed as non-positive values
            if (step != -1 && step < 0) {
                System.err.println("Invalid " + type + " step at index " + i + ": " + step + ". Must be -1, 0, or positive integer.");
                steps.set(i, 0);
            }
        }
        
        // Limit to 30 steps
        if (steps.size() > 30) {
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
}
