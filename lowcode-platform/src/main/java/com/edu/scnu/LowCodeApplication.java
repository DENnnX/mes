package com.edu.scnu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LowCodeApplication {
    private static final Logger log = LoggerFactory.getLogger(LowCodeApplication.class);

    public static void main(String[] args) {
        String dbPath = System.getenv().getOrDefault("DB_PATH", "./data/lowcode.db");
        java.io.File parentDir = new java.io.File(dbPath).getAbsoluteFile().getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        SpringApplication.run(LowCodeApplication.class, args);
        log.info("==================================================================");
        log.info("   Model-Driven Low-Code Platform (Java + DDD + JSON-RPC 2.0)");
        log.info("   Web Console & Dynamic Engine: http://localhost:8088/");
        log.info("   Unified JSON-RPC Endpoint:    http://localhost:8088/rbac_tenant_root/rpc/service");
        log.info("==================================================================");
    }
}
