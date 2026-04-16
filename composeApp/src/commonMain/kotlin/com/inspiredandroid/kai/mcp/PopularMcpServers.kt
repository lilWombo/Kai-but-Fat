package com.inspiredandroid.kai.mcp

import androidx.compose.runtime.Immutable

@Immutable
data class PopularMcpServer(
    val name: String,
    val url: String,
    val description: String,
)

val popularMcpServers = listOf(
    PopularMcpServer(
        name = "Context7",
        url = "https://mcp.context7.com/mcp",
        description = "Up-to-date library and framework docs",
    ),
    PopularMcpServer(
        name = "CoinGecko",
        url = "https://mcp.api.coingecko.com/mcp",
        description = "Real-time crypto prices and market data",
    ),
    PopularMcpServer(
        name = "Manifold Markets",
        url = "https://api.manifold.markets/v0/mcp",
        description = "Prediction market data and odds",
    ),
    PopularMcpServer(
        name = "Fetch",
        url = "https://remote.mcpservers.org/fetch/mcp",
        description = "Fetch web content and convert HTML to markdown",
    ),
    PopularMcpServer(
        name = "DeepWiki",
        url = "https://mcp.deepwiki.com/mcp",
        description = "AI-powered docs for any GitHub repo",
    ),
    PopularMcpServer(
        name = "Sequential Thinking",
        url = "https://remote.mcpservers.org/sequentialthinking/mcp",
        description = "Structured step-by-step problem-solving",
    ),
    PopularMcpServer(
        name = "Find-A-Domain",
        url = "https://api.findadomain.dev/mcp",
        description = "Domain availability across 1,444+ TLDs",
    ),
    PopularMcpServer(
        name = "SubwayInfo NYC",
        url = "https://subwayinfo.nyc/mcp",
        description = "Real-time NYC transit info",
    ),
    PopularMcpServer(
        name = "Jina AI",
        url = "https://mcp.jina.ai/v1",
        description = "Convert URLs to markdown, web search, image search",
    ),
    PopularMcpServer(
        name = "Open-Meteo Weather",
        url = "https://mcp.open-mcp.org/api/server/open-weather@latest/mcp",
        description = "Global weather forecasts and air quality",
    ),
    PopularMcpServer(
        name = "Brave Search",
        url = "https://remote.mcpservers.org/brave-search/mcp",
        description = "Privacy-focused web search with clean results",
    ),
    PopularMcpServer(
        name = "HackerNews",
        url = "https://remote.mcpservers.org/hackernews/mcp",
        description = "Top stories, comments, and jobs from Hacker News",
    ),
    PopularMcpServer(
        name = "ArXiv Papers",
        url = "https://remote.mcpservers.org/arxiv/mcp",
        description = "Search and read academic papers from arXiv.org",
    ),
    PopularMcpServer(
        name = "Wikipedia",
        url = "https://remote.mcpservers.org/wikipedia/mcp",
        description = "Search Wikipedia and retrieve full article content",
    ),
    PopularMcpServer(
        name = "GitHub Public",
        url = "https://remote.mcpservers.org/github/mcp",
        description = "Search public GitHub repos, issues, and code",
    ),
    PopularMcpServer(
        name = "Persistent Memory",
        url = "https://remote.mcpservers.org/memory/mcp",
        description = "Key-value memory that persists across conversations",
    ),
    PopularMcpServer(
        name = "Time & Timezone",
        url = "https://remote.mcpservers.org/time/mcp",
        description = "Current time, timezone conversions, date arithmetic",
    ),
    PopularMcpServer(
        name = "Wolfram Alpha",
        url = "https://remote.mcpservers.org/wolframalpha/mcp",
        description = "Computational knowledge engine — math, science, data",
    ),
)
