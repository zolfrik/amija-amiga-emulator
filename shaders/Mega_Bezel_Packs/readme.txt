Mega Bezel community packs
==========================

This folder is empty on purpose. It is where add-on packs for the Mega Bezel
shader go - the ones that put a photorealistic machine around the picture
instead of the plain generated bezel.

AmiJa ships none of them, and the download assistant does not offer them: the
Mega Bezel chain has not been tested here enough to be recommended, so both it
and the packs are left to whoever wants to try them by hand. Everything below is
a manual install. Once the files are in place the presets show up in the shader
list of the F12 popup like any other .slangp.

1. The Mega Bezel shader itself (HyperspaceMadness, GPL-3.0-or-later). Download
   the release zip and unpack it so that its own Mega_Bezel folder lands here:

     shaders/shaders_slang/bezel/Mega_Bezel/Presets/...
     shaders/shaders_slang/bezel/Mega_Bezel/shaders/...

   The zip has a versioned root folder (Mega_Bezel_V1.17.1_2024-04-14/); drop
   that level, do not keep it.

     https://github.com/HyperspaceMadness/Mega_Bezel

2. A machine pack, if you want photographed hardware instead of the generated
   bezel. The one that matters for an Amiga is TheNamec's Commodore pack -
   bezels for the A500, A500 Plus, A600, A1200, CD32 and CDTV, plus the
   Commodore monitors of the era (C1084, C1702, C1901). It is a 347 MB download
   published under CC BY-NC-ND 4.0, so: credit the author, no commercial use, no
   modified copies. Unzip it here so that the pack's own folders land one level
   down, e.g.

     shaders/Mega_Bezel_Packs/thenamec-commodore-pack/presets/...
     shaders/Mega_Bezel_Packs/thenamec-commodore-pack/res/...

     https://github.com/TheNamec/megabezel-commodore-pack
     https://retrogamingpacks.blogspot.com/

Those depths are not suggestions. The presets inside a pack reach back up to
shaders/shaders_slang/bezel/Mega_Bezel/ by counting folders, and the Mega Bezel
in turn reaches shaders/shaders_slang/include/ and shaders/shaders_slang/blurs/
the same way - those two folders are here already, which is why AmiJa ships
them. One level too many or too few and the presets fail to load.
