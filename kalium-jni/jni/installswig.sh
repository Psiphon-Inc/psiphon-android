set -e

wget http://prdownloads.sourceforge.net/swig/swig-2.0.10.tar.gz
tar -xvf swig-2.0.10.tar.gz
cd swig-2.0.10
./configure
make -j 5
sudo make install
