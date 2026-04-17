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
        name = "DeepWiki",
        url = "https://mcp.deepwiki.com/mcp",
        description = "AI-powered docs for any GitHub repo",
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
        name = "Exa Search",
        url = "https://mcp.exa.ai/mcp",
        description = "AI-powered neural search across the web and research papers",
    ),
    PopularMcpServer(
        name = "Cloudflare Docs",
        url = "https://mcp.cloudflare.com/mcp",
        description = "Search Cloudflare developer documentation and APIs",
    ),
    PopularMcpServer(
        name = "Figma",
        url = "https://mcp.figma.com/mcp",
        description = "Read Figma files, components and design tokens",
    ),
    PopularMcpServer(
        name = "Notion",
        url = "https://mcp.notion.com/mcp",
        description = "Read and write Notion pages and databases",
    ),
    PopularMcpServer(
        name = "Supabase",
        url = "https://mcp.supabase.com/mcp",
        description = "Manage Supabase projects, tables and queries",
    ),
    PopularMcpServer(
        name = "Linear",
        url = "https://mcp.linear.app/mcp",
        description = "Create and manage Linear issues, projects and cycles",
    ),
    PopularMcpServer(
        name = "Fetch",
        url = "https://mcp.jina.ai/v1",
        description = "Fetch any URL and convert to clean markdown for AI reading",
    ),
    PopularMcpServer(
        name = "Sequential Thinking",
        url = "https://mcp.glama.ai/sequentialthinking/mcp",
        description = "Structured step-by-step reasoning and problem-solving",
    ),
)
