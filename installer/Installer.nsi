;Create Unicode Installer
Unicode true

;--------------------------------
;Include Modern UI

  !include "MUI2.nsh"

;--------------------------------
;Include Windows Version checking

  !include "WinVer.nsh"

;--------------------------------
;Include Functions for Files 
;
  !include "FileFunc.nsh"
  
;--------------------------------
;Include Version

  !include "Version.nsh"

;--------------------------------
;General
!define NAME "GitAsSvn" 
!define ARP "Software\Microsoft\Windows\CurrentVersion\Uninstall\${NAME}"



;Name and file
  Name "${Name}"
  OutFile "${Name}_Setup-${VERSION}.exe"

;Default installation folder
  InstallDir "$PROGRAMFILES64\${Name}"

;Request application privileges for Windows Vista
  RequestExecutionLevel admin

;Always show the messages of the installer
ShowInstDetails show

;--------------------------------
;Interface Settings

  !define MUI_ABORTWARNING

;--------------------------------
;Pages

  !insertmacro MUI_PAGE_DIRECTORY
  !insertmacro MUI_PAGE_COMPONENTS
  !insertmacro MUI_PAGE_INSTFILES

  !insertmacro MUI_UNPAGE_CONFIRM
  !insertmacro MUI_UNPAGE_INSTFILES

;--------------------------------
;Languages

  !insertmacro MUI_LANGUAGE "English"

;--------------------------------
;Installer Sections

Function .onInit
  SetRegView 64
  ; do not check if it is installed
  ; newer installations are possible on top of old ones
FunctionEnd

Section "GitAsSvn" SecGitAsSvn 
  DetailPrint "Installing GitAsSvn version ${Version}."
  SetOutPath "$INSTDIR"

  ; install the other files
  DetailPrint "copy files"
  File /r git-as-svn\*


  DetailPrint "writing run file"
  FileOpen $9 run.bat w ;Opens a Empty File an fills it
  FileWrite $9 "cd %~dp0$\r$\n"
  FileWrite $9 "call .\bin\git-as-svn.bat -c $\"$INSTDIR\etc\git-as-svn.conf$\"$\r$\n"
  FileClose $9 ;Closes the filled file

  ;Create uninstaller
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  DetailPrint "Registering GitAsSvn in system"
  ;Add Service to "Programs and Features"
  WriteRegStr HKLM "${ARP}" "DisplayName" "${Name}"  
  WriteRegStr HKLM "${ARP}" "DisplayVersion" "${Version}"
  WriteRegStr HKLM "${ARP}" "UninstallString" "$\"$INSTDIR\Uninstall.exe$\"" 
  WriteRegStr HKLM "${ARP}" "QuietUninstallString" "$\"$INSTDIR\Uninstall.exe$\" /S" 
  
  
SectionEnd

Section "Add to Autostart" SecAutostart

  DetailPrint "Registering GitAsSvn for autostart"
  ;Add LogicClient to autostart of all users
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Run" "${Name}" "cmd.exe /c start /min cmd.exe /c $\"$INSTDIR\run.bat$\""
  
SectionEnd

;--------------------------------
;Uninstaller Section

Section "Uninstall"
  SetRegView 64
  ;Remove Service from "Programs and Features"
  DeleteRegKey HKLM "${ARP}" 

  ;Delete the Autostart if it exists
  DeleteRegValue HKLM "Software\Microsoft\Windows\CurrentVersion\Run" "${Name}" 
  
  Delete "$INSTDIR\Uninstall.exe"

  RMDir /r "$INSTDIR"

SectionEnd
