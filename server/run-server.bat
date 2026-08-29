@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo ============================================
echo   家庭位置共享服务端 - 构建并启动
echo ============================================

rem 检查端口 3000 是否被占用
powershell -NoProfile -Command "$c=Get-NetTCPConnection -LocalPort 3000 -State Listen -ErrorAction SilentlyContinue; if($c){$p=Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue; Write-Host ('[错误] 端口3000被占用 PID='+$c.OwningProcess+' ('+$p.ProcessName+')，请先结束旧进程'); exit 1}"
if errorlevel 1 (
    echo.
    echo 结束旧进程命令示例：taskkill /F /PID 进程号
    pause
    exit /b 1
)

echo [1/2] Maven 构建中（跳过测试）...
call "mvn" -q -DskipTests package
if errorlevel 1 (
    echo.
    echo 构建失败，请查看上方错误信息。
    pause
    exit /b 1
)

echo [2/2] 启动服务端（监听 0.0.0.0:3000，Ctrl+C 停止）...
java -jar target\family-share-server.jar

pause
