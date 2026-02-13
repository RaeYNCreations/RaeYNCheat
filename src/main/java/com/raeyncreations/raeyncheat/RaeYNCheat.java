package com.raeyncreations.raeyncheat;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RaeYNCheat implements ModInitializer {
    
    public static final String MOD_ID = "raeyncheat";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    @Override
    public void onInitialize() {
        LOGGER.info("RaeYNCheat mod initialized");
    }
}
