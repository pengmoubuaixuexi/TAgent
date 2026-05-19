-- V028b: 濉叆 14 涓柊 MCP 鐨?transport_config锛圵indows cmd/npx 鏍煎紡锛?

SET NAMES utf8mb4;
USE `ai-agent-station-study`;

-- 9001 calendar-mcp (Google OAuth 鍐呭祵 JSON)
UPDATE ai_client_tool_mcp SET transport_type='stdio',
transport_config='{"calendar-mcp":{"command":"cmd","args":["/c","npx","@cocal/google-calendar-mcp"],"env":{"GOOGLE_OAUTH_CREDENTIALS":"${GOOGLE_OAUTH_CREDENTIALS_JSON}"}}}'
WHERE mcp_id='9001';

-- 9002 todo-mcp (Todoist)
UPDATE ai_client_tool_mcp SET transport_type='stdio',
transport_config='{"todo-mcp":{"command":"cmd","args":["/c","npx","-y","todoist-mcp"],"env":{"API_KEY":"${TODOIST_API_KEY}"}}}'
WHERE mcp_id='9002';

-- 9003 weather-mcp (Open-Meteo, 鍏嶈垂)
UPDATE ai_client_tool_mcp SET transport_type='stdio',
transport_config='{"weather-mcp":{"command":"cmd","args":["/c","npx","-y","-p","open-meteo-mcp-server","open-meteo-mcp-server"]}}'
WHERE mcp_id='9003';

-- 9004 recipe-mcp 鈫?@cookwith/recipe-mcp (鍏嶈垂)
UPDATE ai_client_tool_mcp SET transport_type='stdio', mcp_name='cookwith-recipe',
transport_config='{"cookwith-recipe":{"command":"cmd","args":["/c","npx","-y","@cookwith/recipe-mcp"]}}'
WHERE mcp_id='9004';

-- 9005 nutrition-mcp (USDA)
UPDATE ai_client_tool_mcp SET transport_type='stdio',
transport_config='{"nutrition-mcp":{"command":"cmd","args":["/c","npx","-y","nutrition-mcp"],"env":{"USDA_API_KEY":"${USDA_API_KEY}"}}}'
WHERE mcp_id='9005';

-- 9006 finance-mcp (Yahoo Finance, 鍏嶈垂) 鈥?鐢?mcp-stdio-wrapper.js 杩囨护 stdout 鏃ュ織
UPDATE ai_client_tool_mcp SET transport_type='stdio',
transport_config='{"finance-mcp":{"command":"cmd","args":["/c","node","docs/dev-ops/mcp-wrappers/mcp-stdio-wrapper.js","npx","stockquotes-mcp","--transport","stdio"]}}'
WHERE mcp_id='9006';

-- 9007 calculator-mcp (鍏嶈垂)
UPDATE ai_client_tool_mcp SET transport_type='stdio',
transport_config='{"calculator-mcp":{"command":"cmd","args":["/c","npx","@cyanheads/calculator-mcp-server@latest"]}}'
WHERE mcp_id='9007';

-- 9008 github-mcp
UPDATE ai_client_tool_mcp SET transport_type='stdio',
transport_config='{"github-mcp":{"command":"cmd","args":["/c","npx","-y","@modelcontextprotocol/server-github"],"env":{"GITHUB_PERSONAL_ACCESS_TOKEN":"${GITHUB_PERSONAL_ACCESS_TOKEN}"}}}'
WHERE mcp_id='9008';

-- 9009 arxiv-mcp (鍏嶈垂)
UPDATE ai_client_tool_mcp SET transport_type='stdio',
transport_config='{"arxiv-mcp":{"command":"cmd","args":["/c","npx","-y","@fre4x/arxiv"]}}'
WHERE mcp_id='9009';

-- 9010 book-mcp 鈫?mcp-douban-server (鍏嶈垂) 鈥?鐢?mcp-stdio-wrapper.js 杩囨护 stdout 鏃ュ織
UPDATE ai_client_tool_mcp SET transport_type='stdio', mcp_name='mcp-douban-server',
transport_config='{"mcp-douban-server":{"command":"cmd","args":["/c","node","docs/dev-ops/mcp-wrappers/mcp-stdio-wrapper.js","npx","mcp-douban-server"]}}'
WHERE mcp_id='9010';

-- 9011 fitness-mcp 鈫?health-fitness-mcp (鍏嶈垂)
UPDATE ai_client_tool_mcp SET transport_type='stdio', mcp_name='health-fitness-mcp',
transport_config='{"health-fitness-mcp":{"command":"cmd","args":["/c","npx","-y","health-fitness-mcp"]}}'
WHERE mcp_id='9011';

-- 9012 translator-mcp 鈫?鐧惧害缈昏瘧
UPDATE ai_client_tool_mcp SET transport_type='stdio', mcp_name='generic-translate',
transport_config='{"generic-translate":{"command":"cmd","args":["/c","npx","-y","@mcpcn/mcp-generic-translate"],"env":{"TRANSLATE_APP_ID":"${TRANSLATE_APP_ID}","TRANSLATE_APP_KEY":"${TRANSLATE_APP_KEY}"}}}'
WHERE mcp_id='9012';

-- 9013 writing-mcp 鈫?languagetool (鍏嶈垂锛屽紑婧愯娉曟鏌ュ紩鎿?
UPDATE ai_client_tool_mcp SET transport_type='stdio', mcp_name='languagetool',
transport_config='{"languagetool":{"command":"cmd","args":["/c","npx","-y","@dpesch/languagetool-mcp-server"]}}'
WHERE mcp_id='9013';

-- 9014 image-gen-mcp 鈫?纭呭熀娴佸姩
UPDATE ai_client_tool_mcp SET transport_type='stdio', mcp_name='siliconflow-image',
transport_config='{"siliconflow-image":{"command":"cmd","args":["/c","npx","-y","siliconflow-image-mcp"],"env":{"SILICONFLOW_API_KEY":"${OPENAI_API_KEY}"}}}'
WHERE mcp_id='9014';

