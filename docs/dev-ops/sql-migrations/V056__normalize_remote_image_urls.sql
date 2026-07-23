-- V056: remove browser-only URI fragments from persisted remote image URLs.
-- Values such as "#pic_center" are not part of the HTTP resource and can make
-- OpenAI-compatible vision gateways silently ignore an otherwise valid image.

UPDATE ai_chat_attachment
SET source_url = SUBSTRING_INDEX(source_url, '#', 1)
WHERE source_type = 'URL'
  AND source_url IS NOT NULL
  AND source_url LIKE '%#%';
