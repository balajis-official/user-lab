package com.userlab.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class Config {

    private static String baseUrl;
    private static String dbUrl;
    private static String dbUser;
    private static String env;
    private static String dbPassword;

    private static final Properties prop = new Properties();

    static {

        env = System.getProperty("env");

        if (env == null) {
            throw new IllegalStateException("Set -Denv=dev, qa or stage, env is null");
        }

        try (InputStream is = Config.class.getResourceAsStream("/config/" + env + ".properties")) {
            if (is == null) {
                throw new IllegalStateException("Config file not found: /config/" + env + ".properties ");
            }

            prop.load(is);
            baseUrl = prop.getProperty("baseUrl");
             if (baseUrl == null) {
                throw new IllegalStateException("Key baseUrl found: /config/" + env + ".properties ");
            }

            dbUrl = prop.getProperty("dbUrl");
             if (dbUrl == null) {
                throw new IllegalStateException("Key dbUrl not found: /config/" + env + ".properties ");
            }

            dbUser = prop.getProperty("dbUser");
             if (dbUser == null) {
                throw new IllegalStateException("Key dbUser not found: /config/" + env + ".properties ");
            }

        } catch (IOException ioe) {
            throw new IllegalStateException("Cannot read config for env: " + env, ioe);
        }

        dbPassword = System.getenv("DB_PASSWORD");
        if (dbPassword == null || dbPassword.isBlank()) {
            throw new IllegalStateException("Environment variable DB_PASSWORD is not set");
        }

    }

    public static  String getBaseUrl() {
        return baseUrl;
    }

    public static String getEnv() {
        return env;
    }

    public static String getDBPassword() {
        return dbPassword;
    }

    public static String getDBUrl(){
        return dbUrl;
    }

    public static String getDBUser(){
        return dbUser;
    }

}
