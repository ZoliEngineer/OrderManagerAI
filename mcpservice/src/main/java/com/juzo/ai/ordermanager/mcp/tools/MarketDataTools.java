package com.juzo.ai.ordermanager.mcp.tools;

import com.juzo.ai.ordermanager.mcp.client.MarketDataClient;
import com.juzo.ai.ordermanager.mcp.dto.StockPrice;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class MarketDataTools {

    private final MarketDataClient marketDataClient;

    public MarketDataTools(MarketDataClient marketDataClient) {
        this.marketDataClient = marketDataClient;
    }

    @McpTool(name = "get_market_prices",
            title = "Get market prices",
            description = """
                    Returns the latest price snapshot for the stocks tracked by the platform \
                    (ticker, name, last price, open/high/low, previous close, change and change percent). \
                    Pass 'tickers' to limit the result; omit it to get every tracked ticker. \
                    Unknown tickers are silently omitted, so an empty list means none of them are tracked.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = true))
    public List<StockPrice> getMarketPrices(
            McpSyncRequestContext context,
            @McpToolParam(required = false, description = "Ticker symbols to return, e.g. [\"AAPL\", \"MSFT\"]. Omit for all.")
            List<String> tickers) {
        CallerContext caller = CallerContext.from(context);
        List<StockPrice> prices = marketDataClient.getPrices(caller.bearerToken());
        if (tickers == null || tickers.isEmpty()) {
            return prices;
        }
        Set<String> wanted = tickers.stream()
                .map(t -> t.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return prices.stream().filter(p -> wanted.contains(p.ticker())).toList();
    }
}
