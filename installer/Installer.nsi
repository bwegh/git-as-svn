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
  !include "StrFunc.nsh"
   ${StrRep}
  
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
  InstallDir "D:\${Name}"

;Request application privileges for Windows Vista
  RequestExecutionLevel user

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

  IfFileExists "$INSTDIR\etc\git-as-svn.conf" copyConfig writeConfig
  Goto writeConfig

copyConfig:
  CopyFiles "$INSTDIR\etc\git-as-svn.conf" "$INSTDIR\etc\git-as-svn.back"


writeConfig:
  ${StrRep} $6 "$INSTDIR" "\" "/"
  
  DetailPrint "adjusting config file using $6"
  ClearErrors
  FileOpen $0 "etc\git-as-svn.conf" "r"        ; open target file for reading
  GetTempFileName $R0                          ; get new temp file name
  FileOpen $1 $R0 "w"                          ; open temp file for writing
  loop:
   FileRead $0 $2                              ; read line from target file
   IfErrors done                               ; check if end of file reached
   ${StrRep} $2 $2 "<<INSTDIR>>" "$6"       ;change the line
   FileWrite $1 $2                             ; write changed or unchanged line to temp file
   Goto loop
 
  done:
   FileClose $0                                ; close target file
   FileClose $1                                ; close temp file
   Delete "file.txt"                           ; delete target file
   CopyFiles /SILENT $R0 "etc\git-as-svn.conf"            ; copy temp file to target file
   Delete $R0

  DetailPrint "writing run file"
  FileOpen $9 run.bat w ;Opens a Empty File an fills it
  FileWrite $9 "call %~dp0\bin\git-as-svn.bat -c $\"$INSTDIR\etc\git-as-svn.conf$\"$\r$\n"
  FileClose $9 ;Closes the filled file

  ;Create uninstaller
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  DetailPrint "Registering GitAsSvn in system"
  ;Add Service to "Programs and Features"
  WriteRegStr HKCU "${ARP}" "DisplayName" "${Name}"  
  WriteRegStr HKCU "${ARP}" "DisplayVersion" "${Version}"
  WriteRegStr HKCU "${ARP}" "UninstallString" "$\"$INSTDIR\Uninstall.exe$\"" 
  WriteRegStr HKCU "${ARP}" "QuietUninstallString" "$\"$INSTDIR\Uninstall.exe$\" /S" 
  
  ;Delete the Autostart if it exists
  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "${Name}" 
  
SectionEnd

Section "Add to Autostart" SecAutostart

  DetailPrint "Registering GitAsSvn for autostart"
  ;Add LogicClient to autostart of all users
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "${Name}" "cmd.exe /c start /min cmd.exe /c $\"$INSTDIR\run.bat$\""
  
SectionEnd

;--------------------------------
;Uninstaller Section

Section "Uninstall"
  SetRegView 64
  ;Remove Service from "Programs and Features"
  DeleteRegKey HKLM "${ARP}" 

  ;Delete the Autostart if it exists
  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "${Name}" 
  
  Delete "$INSTDIR\Uninstall.exe"

  RMDir /r "$INSTDIR"

SectionEnd
