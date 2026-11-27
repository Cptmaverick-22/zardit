; Inno Setup script for Zardit (Windows x64)

#ifndef MyAppVersion
  #define MyAppVersion "1.4.2"
#endif

[Setup]
AppId={{7B2E6E2B-8D9A-4F6D-9B3C-2C8B6A9F1D12}
AppName=Zardit
AppVersion={#MyAppVersion}
AppVerName=Zardit {#MyAppVersion}
AppPublisher=Cptmaverick-22
AppPublisherURL=https://github.com/Cptmaverick-22/zardit
AppSupportURL=https://github.com/Cptmaverick-22/zardit/issues
DefaultDirName={autopf}\Zardit
DefaultGroupName=Zardit
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
OutputDir=..\..\dist
OutputBaseFilename=Zardit-{#MyAppVersion}-win64-setup
SetupIconFile=..\..\assets\zardit.ico
UninstallDisplayIcon={app}\Zardit.exe
LicenseFile=..\..\LICENSE
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
ChangesEnvironment=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
Name: "addtopath"; Description: "Add Zardit CLI folder to your user PATH (for zardit-cli)"; GroupDescription: "Command line:"

[Files]
Source: "..\..\dist\Zardit\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\..\LICENSE"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\Zardit"; Filename: "{app}\Zardit.exe"; IconFilename: "{app}\Zardit.exe"; IconIndex: 0; Comment: "Blaze through videos, audio & playlists across every platform"
Name: "{autodesktop}\Zardit"; Filename: "{app}\Zardit.exe"; IconFilename: "{app}\Zardit.exe"; IconIndex: 0; Tasks: desktopicon; Comment: "Blaze through videos, audio & playlists across every platform"

[Registry]
Root: HKA; Subkey: "Environment"; ValueType: expandsz; ValueName: "Path"; ValueData: "{olddata};{app}"; Tasks: addtopath; Check: NeedsAddPath(ExpandConstant('{app}'))

[Run]
Filename: "{app}\Zardit.exe"; Description: "{cm:LaunchProgram,Zardit}"; Flags: nowait postinstall skipifsilent

[Code]
function NeedsAddPath(Param: string): boolean;
var
  OrigPath: string;
begin
  if not RegQueryStringValue(HKEY_CURRENT_USER, 'Environment', 'Path', OrigPath) then
  begin
    Result := True;
    exit;
  end;
  Result := Pos(';' + LowerCase(Param) + ';', ';' + LowerCase(OrigPath) + ';') = 0;
end;
