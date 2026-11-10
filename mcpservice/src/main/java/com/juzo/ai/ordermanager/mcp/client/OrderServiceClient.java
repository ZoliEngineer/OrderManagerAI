package com.juzo.ai.ordermanager.mcp.client;

import com.juzo.ai.ordermanager.mcp.dto.OrderServiceResponse;
import com.juzo.ai.ordermanager.mcp.dto.PlaceOrderCommand;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

@Component
public class OrderServiceClient {

    private final RestClient restClient;

    public OrderServiceClient(@Qualifier("orderServiceRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Places an order. Returns the Order Service response for both acceptance (201) and
     * risk rejection (422); any other error status raises {@link DownstreamException}.
     */
    public OrderServiceResponse placeOrder(PlaceOrderCommand command, String bearerToken) {
        try {
            return restClient.post()
                    .uri("/api/orders")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(command)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 201 || status == 200 || status == 422) {
                            OrderServiceResponse body = response.bodyTo(OrderServiceResponse.class);
                            if (body == null) {
                                throw new DownstreamException(status, "Order Service returned an empty response");
                            }
                            return body;
                        }
                        throw new DownstreamException(status, describe("Order Service", status, response.getBody().readAllBytes()));
                    });
        } catch (DownstreamException e) {
            throw e;
        } catch (RestClientException e) {
            // Includes read timeouts: the order may or may not have been created.
            throw new DownstreamException("Order Service unavailable or timed out; the order status is unknown", e);
        }
    }

    /** Cancels an order; 404/409 and other errors raise {@link DownstreamException}. */
    public void cancelOrder(UUID orderId, String bearerToken) {
        try {
            restClient.delete()
                    .uri("/api/orders/{id}", orderId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            throw new DownstreamException(status, describe("Order Service", status, e.getResponseBodyAsByteArray()));
        } catch (RestClientException e) {
            throw new DownstreamException("Order Service unavailable", e);
        }
    }

    private static String describe(String service, int status, byte[] body) {
        String text = body == null ? "" : new String(body).trim();
        if (text.length() > 300) text = text.substring(0, 300);
        return service + " returned HTTP " + status + (text.isEmpty() ? "" : ": " + text);
    }
}
