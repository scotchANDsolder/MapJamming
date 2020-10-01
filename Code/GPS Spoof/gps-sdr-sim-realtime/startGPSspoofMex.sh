#!/bin/bash
mkfifo gpsstream
curyear=$(date +"%Y")
a=${curyear:2,2}
doy=$(date +%j)
brdcFile="brdc"$doy"0."$a"n"
wget ftp://cddis.gsfc.nasa.gov/gnss/data/daily/$curyear/brdc/$brdcFile.Z
uncompress $brdcFile.Z

hackrf_transfer -t ./gpsstream -f 1575420000 -s 2600000 -a 1 -x 30 &
./gps-sdr-sim -e $brdcFile -u MexBorder.csv -b 8 -i -o gpsstream

