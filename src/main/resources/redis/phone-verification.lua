-- All keys are explicit KEYS entries and share one cluster hash slot.
-- Numeric timestamps are milliseconds from Redis, never application clocks.
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local key = KEYS[1]
local op = ARGV[1]
local function get(field) return redis.call('HGET', key, field) end
local function live(field) return tonumber(get(field) or '0') > now end

if op == 'reserve' then
    local limits = {5, 5, 20, 200}
    for i = 2, 5 do
        redis.call('ZREMRANGEBYSCORE', KEYS[i], '-inf', now - 3600000)
        if redis.call('ZCARD', KEYS[i]) >= limits[i - 1] then return 429 end
        if i <= 3 then
            local last = redis.call('ZREVRANGE', KEYS[i], 0, 0, 'WITHSCORES')
            if #last > 0 and tonumber(last[2]) + 60000 > now then return 429 end
        end
    end
    for i = 2, 5 do
        redis.call('ZADD', KEYS[i], now, ARGV[2])
        redis.call('PEXPIRE', KEYS[i], 3600000)
    end
    redis.call('HSET', key, 'pending', ARGV[2], 'pendingPhone', ARGV[3],
        'pendingCode', ARGV[4], 'pendingExpires', now + 180000)
    redis.call('PEXPIRE', key, 3600000)
    return 1
elseif op == 'cancel' then
    if get('pending') == ARGV[2] then
        redis.call('HDEL', key, 'pending', 'pendingPhone', 'pendingCode', 'pendingExpires')
    end
    return 1
elseif op == 'activate' then
    if get('pending') ~= ARGV[2] or not live('pendingExpires') then return 0 end
    local phone, code = get('pendingPhone'), get('pendingCode')
    redis.call('DEL', key)
    redis.call('HSET', key, 'challenge', ARGV[2], 'phone', phone, 'code', code,
        'expires', now + 180000, 'failures', 0)
    redis.call('PEXPIRE', key, 180000)
    return 1
elseif op == 'verify' then
    if get('challenge') ~= ARGV[2] or get('phone') ~= ARGV[3] or not live('expires') then return 0 end
    if get('code') ~= ARGV[4] then
        if redis.call('HINCRBY', key, 'failures', 1) >= 5 then
            redis.call('HDEL', key, 'challenge', 'code', 'expires', 'failures')
        end
        return 0
    end
    redis.call('HDEL', key, 'challenge', 'code', 'expires', 'failures')
    redis.call('HSET', key, 'ticket', ARGV[5], 'ticketExpires', now + 300000)
    redis.call('PEXPIRE', key, 300000)
    return 1
elseif op == 'consume' then
    if get('ticket') ~= ARGV[2] or get('phone') ~= ARGV[3] or not live('ticketExpires') then return 0 end
    redis.call('HDEL', key, 'ticket', 'ticketExpires')
    return 1
end
return 0
