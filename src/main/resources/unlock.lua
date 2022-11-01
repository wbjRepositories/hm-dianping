-- 锁的id KEYS[1]
-- 当前线程标识 ARGV[1]

local id = redis.call("get",KEYS[1])
-- 判断是否与当前线程标识一致
if(ARGV[1] == id) then
    -- 释放锁
    return redis.call("del",KEYS[1])
end
return 0