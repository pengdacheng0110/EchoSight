@echo off
chcp 65001 >nul
cd /d "%~dp0"

rem 优先用项目内的虚拟环境，没有就退回 py 启动器 / PATH 里的 python。
rem 原先这里写死 buildenv\Scripts\python.exe，而 buildenv 是 gitignore 的 ——
rem 新克隆下来双击必然报"系统找不到指定的路径"，README 却写着"或双击 run.bat"。
set "PY=buildenv\Scripts\python.exe"
if exist "%PY%" goto run

set "PY=py"
"%PY%" --version >nul 2>nul
if not errorlevel 1 goto run

set "PY=python"
"%PY%" --version >nul 2>nul
if not errorlevel 1 goto run

echo 没有找到可用的 Python。
echo 请先安装 Python 3.10+，或在项目目录内建好虚拟环境：
echo     py -m venv buildenv
echo     buildenv\Scripts\pip install -r requirements.txt
echo.
pause
exit /b 1

:run
echo 正在启动盲人识物助手（%PY%）...
echo.
"%PY%" blind_assistant.py
echo.
pause
