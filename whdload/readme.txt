WHDLoad system files go here, in a subfolder named WHDLoad_files.

The easiest way is to let AmiJa fetch them for you: press F12, then
"Optional files" -> "Download...". It downloads WHDLoad from whdload.de and
the .RTB relocation tables from Aminet, straight from the official sites, and
unpacks them into the right place. Nothing is downloaded without your consent.

To do it by hand, the layout is:

    whdload/WHDLoad_files/C/WHDLoad              from https://whdload.de/
    whdload/WHDLoad_files/Devs/Kickstarts/*.RTB  from aminet.net/util/boot/skick346.lha
    whdload/WHDLoad_files/C/{SetPatch,Assign,MakeDir}   (optional)

The last three are AmigaOS commands, and they are optional: WHDLoad starts
without them. If you want them, they come from your own Workbench 3.x install.
AmiJa cannot download those for you and cannot ship them.

Kickstart ROM dumps are matched from your configured kickstart folder by CRC32,
so you do not need to copy them here.

None of these files are part of AmiJa and none can be redistributed with it -
see THIRD-PARTY-NOTICES.md, sections 4 and 5.
