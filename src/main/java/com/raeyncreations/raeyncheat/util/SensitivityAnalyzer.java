package com.raeyncreations.raeyncheat.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Utility for analyzing differences between client and server mod lists
 * to detect potential false positives
 */
public class SensitivityAnalyzer {
    
    /**
     * Result of sensitivity analysis
     */
    public static class AnalysisResult {
        public final int addedFiles;
        public final int removedFiles;
        public final int modifiedFiles;
        public final int totalDifferences;
        public final SensitivityLevel level;
        public final String message;
        
        public AnalysisResult(int addedFiles, int removedFiles, int modifiedFiles, 
                            SensitivityLevel level, String message) {
            this.addedFiles = addedFiles;
            this.removedFiles = removedFiles;
            this.modifiedFiles = modifiedFiles;
            this.totalDifferences = addedFiles + removedFiles + modifiedFiles;
            this.level = level;
            this.message = message;
        }
    }
    
    /**
     * Sensitivity levels for violation detection
     */
    public enum SensitivityLevel {
        NO_DIFFERENCE,      // No differences detected
        LOW_DIFFERENCE,     // 1-2 files different - might be intentional testing
        MEDIUM_DIFFERENCE,  // 3-9 files different - suspicious
        HIGH_DIFFERENCE,    // 10+ files different - might be accidental (wrong modpack)
        TOTAL_MISMATCH      // Completely different mod list
    }
    
    /**
     * Analyze differences between client and server checksums
     */
    public static AnalysisResult analyzeDifferences(
            List<ChecksumUtil.FileChecksum> clientChecksums,
            List<ChecksumUtil.FileChecksum> serverChecksums,
            int lowThreshold,
            int highThreshold) {
        
        // Use HashMap for O(1) lookups instead of O(n) nested loops
        java.util.Map<String, ChecksumUtil.FileChecksum> clientMap = new java.util.HashMap<>();
        java.util.Map<String, ChecksumUtil.FileChecksum> serverMap = new java.util.HashMap<>();
        
        for (ChecksumUtil.FileChecksum cs : clientChecksums) {
            clientMap.put(cs.fileName, cs);
        }
        for (ChecksumUtil.FileChecksum cs : serverChecksums) {
            serverMap.put(cs.fileName, cs);
        }
        
        Set<String> clientFileNames = clientMap.keySet();
        Set<String> serverFileNames = serverMap.keySet();
        
        // Find added files (in client but not on server)
        Set<String> added = new HashSet<>(clientFileNames);
        added.removeAll(serverFileNames);
        
        // Find removed files (on server but not in client)
        Set<String> removed = new HashSet<>(serverFileNames);
        removed.removeAll(clientFileNames);
        
        // Find modified files (same name but different checksum) - O(n) instead of O(n*m)
        int modified = 0;
        for (String fileName : clientFileNames) {
            if (serverMap.containsKey(fileName)) {
                ChecksumUtil.FileChecksum clientCs = clientMap.get(fileName);
                ChecksumUtil.FileChecksum serverCs = serverMap.get(fileName);
                // Compare checksums
                if (!clientCs.sha256.equals(serverCs.sha256)) {
                    modified++;
                }
            }
        }
        
        int totalDiff = added.size() + removed.size() + modified;
        
        // Determine sensitivity level
        SensitivityLevel level;
        String message;
        
        if (totalDiff == 0) {
            level = SensitivityLevel.NO_DIFFERENCE;
            message = "No differences detected";
        } else if (totalDiff <= lowThreshold) {
            level = SensitivityLevel.LOW_DIFFERENCE;
            message = String.format("Low difference (%d files) - possible intentional testing", totalDiff);
        } else if (totalDiff < highThreshold) {
            level = SensitivityLevel.MEDIUM_DIFFERENCE;
            message = String.format("Medium difference (%d files) - suspicious activity", totalDiff);
        } else if (totalDiff >= highThreshold && totalDiff < serverFileNames.size() * 0.8) {
            level = SensitivityLevel.HIGH_DIFFERENCE;
            message = String.format("High difference (%d files) - possible wrong modpack", totalDiff);
        } else {
            level = SensitivityLevel.TOTAL_MISMATCH;
            message = String.format("Total mismatch (%d files) - completely different mod list", totalDiff);
        }
        
        return new AnalysisResult(added.size(), removed.size(), modified, level, message);
    }
    
    /**
     * Determine if punishment should be applied based on sensitivity level
     */
    public static boolean shouldApplyPunishment(SensitivityLevel level, boolean strictMode) {
        switch (level) {
            case NO_DIFFERENCE:
                return false;
            case LOW_DIFFERENCE:
                // Only punish in strict mode for low differences
                return strictMode;
            case MEDIUM_DIFFERENCE:
                // Always punish medium differences
                return true;
            case HIGH_DIFFERENCE:
                // High differences might be accidents - warn but don't punish heavily
                return false;
            case TOTAL_MISMATCH:
                // Total mismatch might also be an accident (wrong installation)
                return false;
            default:
                return true;
        }
    }
}
