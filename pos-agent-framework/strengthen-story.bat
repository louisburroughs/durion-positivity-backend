@echo off
REM Story Strengthening Agent - Windows Batch Execution Script
REM Usage: strengthen-story.bat <repository> <issue-number> [options]

setlocal enabledelayedexpansion

REM Default values
set OUTPUT_DIR=./story-output
set BYPASS_SSL=

REM Check for help flag
if "%1"=="--help" goto :usage
if "%1"=="-h" goto :usage
if "%1"=="/?" goto :usage

REM Check minimum arguments
if "%1"=="" goto :missing_args
if "%2"=="" goto :missing_args

REM Parse arguments
set REPOSITORY=%1
set ISSUE_NUMBER=%2
shift
shift

REM Parse options
:parse_options
if "%1"=="" goto :check_token
if "%1"=="--output-dir" (
    set OUTPUT_DIR=%2
    shift
    shift
    goto :parse_options
)
if "%1"=="--bypass-ssl" (
    set BYPASS_SSL=--bypass-ssl
    shift
    goto :parse_options
)
echo [91mError: Unknown option: %1[0m
goto :usage

:check_token
REM Check for GitHub token
if "%GITHUB_TOKEN%"=="" (
    echo [91mError: GITHUB_TOKEN environment variable not set[0m
    echo.
    echo Please set your GitHub personal access token:
    echo   set GITHUB_TOKEN=your_token_here
    echo.
    exit /b 1
)

REM Display configuration
echo [94mStory Strengthening Agent[0m
echo ==============================
echo [94mRepository:[0m %REPOSITORY%
echo [94mIssue Number:[0m %ISSUE_NUMBER%
echo [94mOutput Directory:[0m %OUTPUT_DIR%
if not "%BYPASS_SSL%"=="" (
    echo [93mSSL Bypass:[0m Enabled ^(development mode^)
)
echo.

REM Check if Maven wrapper exists
if not exist "..\..\mvnw.cmd" (
    echo [91mError: Maven wrapper not found[0m
    echo Please run this script from the pos-agent-framework directory
    exit /b 1
)

REM Build the project
echo [94mBuilding project...[0m
cd ..\..
call mvnw.cmd clean compile -pl pos-agent-framework -am -q
if errorlevel 1 (
    echo [91mBuild failed[0m
    exit /b 1
)
echo [92mBuild successful[0m
echo.

REM Run the Story Strengthening System
echo [94mStarting story strengthening process...[0m
echo.

call mvnw.cmd exec:java ^
    -pl pos-agent-framework ^
    -Dexec.mainClass="com.pos.agent.story.StoryStrengtheningSystem" ^
    -Dexec.args="%REPOSITORY% %ISSUE_NUMBER% --output-dir %OUTPUT_DIR% %BYPASS_SSL%" ^
    -Dexec.cleanupDaemonThreads=false

set EXIT_CODE=%errorlevel%

echo.
if %EXIT_CODE%==0 (
    echo [92mStory strengthening completed successfully![0m
) else (
    echo [91mStory strengthening failed with exit code: %EXIT_CODE%[0m
)

exit /b %EXIT_CODE%

:missing_args
echo [91mError: Missing required arguments[0m
echo.
goto :usage

:usage
echo Story Strengthening Agent - Execution Script
echo.
echo Usage: %0 ^<repository^> ^<issue-number^> [options]
echo.
echo Arguments:
echo   ^<repository^>       Repository in format 'owner/repo'
echo   ^<issue-number^>     Issue number to process
echo.
echo Options:
echo   --output-dir ^<path^>    Output directory ^(default: ./story-output^)
echo   --bypass-ssl           Bypass SSL certificate validation ^(development only^)
echo   --help                 Display this help message
echo.
echo Environment Variables:
echo   GITHUB_TOKEN           GitHub personal access token ^(required^)
echo.
echo Example:
echo   %0 louisburroughs/durion-positivity-backend 123
echo   %0 louisburroughs/durion-positivity-backend 123 --output-dir ./output --bypass-ssl
exit /b 1
