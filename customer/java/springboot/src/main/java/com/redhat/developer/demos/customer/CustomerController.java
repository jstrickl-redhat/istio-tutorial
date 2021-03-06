package com.redhat.developer.demos.customer;

import io.opentracing.Tracer;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@RestController
public class CustomerController {

    private static final String RESPONSE_STRING_FORMAT = "customer => %s\n";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final RestTemplate restTemplate;

    @Value("${preferences.api.url:http://preference:8080}")
    private String remoteURL;
    
    private static final String HOSTNAME = parseContainerIdFromHostname(
            System.getenv().getOrDefault("HOSTNAME", "unknown"));

    static String parseContainerIdFromHostname(String hostname) {
        return hostname.replaceAll("recommendation-v\\d+-", "");
    }

    @Autowired
    private Tracer tracer;

    public CustomerController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }
    
    // SB 1.5.X actuator does not allow subpaths on custom health checks URL/do in easy way
    @RequestMapping("/health/ready")
    @ResponseStatus(HttpStatus.OK)
    public void ready() {}

    // SB 1.5.X actuator does not allow subpaths on custom health checks URL/do in
    // easy way
    @RequestMapping("/health/live")
    @ResponseStatus(HttpStatus.OK)
    public void live() {}

    public String getAppVersion() {
        String version = System.getenv("customerVersion");
        if (version == null || version.trim().isEmpty()) {
            version = "unknown";
        }
        return version;
    }

    @RequestMapping(value = "/", method = RequestMethod.POST, consumes = "text/plain")
    public ResponseEntity<String> addRecommendation(@RequestBody String body) {
        try {
            logger.info("Adding Recommendation on version: " + this.getAppVersion() );
            
            String response = restTemplate.postForEntity(remoteURL, body, String.class).getBody();
            HttpHeaders headers = new HttpHeaders();
            headers.add("App-Version", this.getAppVersion());
            headers.add("HOST-NAME", HOSTNAME);
            return ResponseEntity.ok().headers(headers).body(String.format(RESPONSE_STRING_FORMAT, response.trim()));

        } catch (HttpStatusCodeException ex) {
            logger.warn("Exception trying to post to preference service.", ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(String.format("%d %s", ex.getRawStatusCode(), createHttpErrorResponseString(ex)));
        } catch (RestClientException ex) {
            logger.warn("Exception trying to post to preference service.", ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ex.getMessage());
        }
    }

    @RequestMapping(value = "/", method = RequestMethod.GET)
    public ResponseEntity<String> getCustomer(@RequestHeader("User-Agent") String userAgent, @RequestHeader(value = "user-preference", required = false) String userPreference) {
        try {
            /**
             * Set baggage
             */
            tracer.activeSpan().setBaggageItem("user-agent", userAgent);
            if (userPreference != null && !userPreference.isEmpty()) {
                tracer.activeSpan().setBaggageItem("user-preference", userPreference);
            }

            String response = restTemplate.getForEntity(remoteURL, String.class).getBody();
            HttpHeaders headers = new HttpHeaders();
            headers.add("App-Version", this.getAppVersion());
            headers.add("HOST-NAME", HOSTNAME);
            return ResponseEntity.ok().headers(headers).body(String.format(RESPONSE_STRING_FORMAT, response.trim()));

        } catch (HttpStatusCodeException ex) {
            logger.warn("Exception trying to get the response from preference service.", ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(String.format(RESPONSE_STRING_FORMAT,
                            String.format("%d %s", ex.getRawStatusCode(), createHttpErrorResponseString(ex))));
        } catch (RestClientException ex) {
            logger.warn("Exception trying to get the response from preference service.", ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(String.format(RESPONSE_STRING_FORMAT, ex.getMessage()));
        }
    }

    private String createHttpErrorResponseString(HttpStatusCodeException ex) {
        String responseBody = ex.getResponseBodyAsString().trim();
        if (responseBody.startsWith("null")) {
            return ex.getStatusCode().getReasonPhrase();
        }
        return responseBody;
    }

}
