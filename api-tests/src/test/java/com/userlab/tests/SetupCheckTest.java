package com.userlab.tests;

import io.restassured.RestAssured;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.userlab.config.Config;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchema;
import static org.hamcrest.Matchers.equalTo;
import static org.testng.Assert.assertEquals;

/**
 * Wiring check only. It proves: the service is reachable, JSON paths work,
 * Jackson can map JSON to a Java type, and the schema validator loads.
 * Delete it once your real tests exist.
 */
public class SetupCheckTest {

    /** Only the fields we need. The rest of the JSON is ignored. */
    public record Health(String status) {}

    @BeforeClass
    public void setUp() {
        RestAssured.baseURI = Config.getBaseUrl();
        System.out.println("[" + Config.getBaseUrl() + "]");
    }

    @Test
    public void serviceIsUp() {
        given().when().get("/actuator/health")
               .then().statusCode(200).body("status", equalTo("UP"));
    }

    @Test
    public void jacksonMapsJsonToJavaType() {
        Health h = given().when().get("/actuator/health").then().statusCode(200).extract().as(Health.class);
        assertEquals(h.status(), "UP");
    }

    @Test
    public void schemaValidatorLoads() {
        given().when().get("/actuator/health")
               .then().body(matchesJsonSchema("{\"type\":\"object\",\"required\":[\"status\"]}"));
    }
}
