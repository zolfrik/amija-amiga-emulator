Floppy drive sounds
===================

These are the samples behind the mechanical noise of the drives: the seek
clicks, the motor, the disk going in. They are part of AmiJa - nothing to
download, nothing to supply. Set the volume from F12 -> "Floppy sound", or with
floppy.sound.volume in amiga.properties; 0 turns the whole thing off.

    drive_click_A500.wav     head step
    drive_spin_A500.wav      motor running, disk in the drive (looped)
    drive_spinnd_A500.wav    motor running, no disk (looped, optional)
    drive_startup_A500.wav   motor starting, played over the spin loop
    drive_snatch_A500.wav    disk inserted

Where they come from
--------------------

They are not recordings. AmiJa generates them from scratch - oscillators, noise
and filters - from a model of what a 3.5" Amiga drive actually is: a brushless
motor turning the disk at 300 rpm and a stepper motor moving the head. The
motor's hum is its commutation harmonics, the once-per-revolution swell is the
disk never being perfectly balanced, a head step is the impact ringing the
case's resonances.

That is why they can ship with AmiJa at all. The usual drive samples going
around are recordings of real hardware carrying no licence, which an emulator
may not redistribute; these are original works, covered by AmiJa's own licence
(GPL-3.0-or-later) like the rest of the program.

Using your own instead
----------------------

Drop your own .wav files in here with the names above and AmiJa will use them -
they are read from this folder, not from the jar. Delete a file and its sound
goes away; empty the folder and the drives fall silent, exactly as with
floppy.sound.volume=0.

Anything AudioSystem can read will load, but two details matter if you want it
to sound right:

  * the click file is not played whole. AmiJa looks for the loudest sample in it
    and takes a 63 ms window starting just before that peak, so a file holding
    one clean click works best - a long recording with many clicks will only
    ever yield its loudest one;
  * the spin files are looped continuously. Unless the loop point is seamless
    you will hear a tick every time it comes round.

The five files here are 16-bit mono, 44.1 kHz except the click at 48 kHz. AmiJa
keeps whatever sample rate it finds, so yours need not match.
