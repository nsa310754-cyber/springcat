-- 実行モード用のサンプル:  /run hello.lua run
local a = "a"
print(a)
local t = {}
for i = 1, 5 do t[#t + 1] = i * i end
print(table.concat(t, " "))
