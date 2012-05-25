package ch.ethz.ssh2.signature;

import java.math.BigInteger;

/**
 * DSASignature.
 * 
 * @author Christian Plattner
 * @version 2.50, 03/15/10
 */
public class DSASignature
{
	private BigInteger r;
	private BigInteger s;

	public DSASignature(BigInteger r, BigInteger s)
	{
		this.r = r;
		this.s = s;
	}

	public BigInteger getR()
	{
		return r;
	}

	public BigInteger getS()
	{
		return s;
	}
}
