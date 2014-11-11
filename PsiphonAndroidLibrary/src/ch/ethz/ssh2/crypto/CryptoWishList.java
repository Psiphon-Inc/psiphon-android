
package ch.ethz.ssh2.crypto;

import ch.ethz.ssh2.compression.CompressionFactory;
import ch.ethz.ssh2.crypto.cipher.BlockCipherFactory;
import ch.ethz.ssh2.crypto.digest.MAC;
import ch.ethz.ssh2.transport.KexManager;

/*
 * Compression implementation from:
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2007 Kenny Root, Jeffrey Sharkey
 */

/**
 * CryptoWishList.
 * 
 * @author Christian Plattner
 * @version 2.50, 03/15/10
 */
public class CryptoWishList
{
	public String[] kexAlgorithms = KexManager.getDefaultKexAlgorithmList();
	public String[] serverHostKeyAlgorithms = KexManager.getDefaultServerHostkeyAlgorithmList();
	public String[] c2s_enc_algos = BlockCipherFactory.getDefaultCipherList();
	public String[] s2c_enc_algos = BlockCipherFactory.getDefaultCipherList();
	public String[] c2s_mac_algos = MAC.getMacList();
	public String[] s2c_mac_algos = MAC.getMacList();
    public String[] c2s_comp_algos = CompressionFactory.getDefaultCompressorList();
    public String[] s2c_comp_algos = CompressionFactory.getDefaultCompressorList();
}
