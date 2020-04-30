@echo off
cd %~dp0
cd ..
call gradlew.bat clean
call gradlew.bat distZip

cd %~dp0
copy ..\build\distributions\git-as-svn*.zip .
call "C:\Program Files\7-Zip\7z.exe" x -aoa git-as-svn*.zip
del git-as-svn*.zip

cd git-as-svn*
for /f "delims=" %%A in ('cd') do (
     set foldername=%%~nxA
    )
cd ..

echo using foldername %foldername%

FOR /f "tokens=1,2 delims=_" %%a IN ("%foldername%") do (
	 set version=%%b
	)
echo using version %version%
echo. !define VERSION "%version%" > Version.nsh

move git-as-svn* git-as-svn 
mkdir git-as-svn\etc
copy git-as-svn\doc\examples\config.yml git-as-svn\etc\git-as-svn.conf

if exist config.yml (
	echo using local configuration
	copy config.yml git-as-svn\etc\git-as-svn.conf
)

call "C:\Program Files (x86)\NSIS\makensis.exe"  Installer.nsi
rmdir /S /Q git-as-svn
del /Q Version.nsh
pause