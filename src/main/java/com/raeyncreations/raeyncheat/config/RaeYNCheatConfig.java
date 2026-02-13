package com.raeyncreations.raeyncheat.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class RaeYNCheatConfig {
    
    public boolean enablePunishmentSystem = true;
    public List<Integer> punishmentSteps = createDefaultPunishmentSteps();
    
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
        // Validate punishment steps
        for (int i = 0; i < punishmentSteps.size(); i++) {
            int step = punishmentSteps.get(i);
            if (step < -1) {
                System.err.println("Invalid punishment step at index " + i + ": " + step + ". Must be -1, 0, or positive integer.");
                punishmentSteps.set(i, 0);
            }
            // Only -1 and 0 are allowed as non-positive values
            if (step == -1 || step >= 0) {
                // Valid
            } else {
                System.err.println("Invalid punishment step at index " + i + ": " + step + ". Must be -1, 0, or positive integer.");
                punishmentSteps.set(i, 0);
            }
        }
        
        // Limit to 30 steps
        if (punishmentSteps.size() > 30) {
            punishmentSteps = punishmentSteps.subList(0, 30);
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
    
    public int getPunishmentDuration(int violationCount) {
        if (violationCount <= 0 || punishmentSteps.isEmpty()) {
            return 0;
        }
        
        // Use the last step if violations exceed step count
        int index = Math.min(violationCount - 1, punishmentSteps.size() - 1);
        return punishmentSteps.get(index);
    }
}
