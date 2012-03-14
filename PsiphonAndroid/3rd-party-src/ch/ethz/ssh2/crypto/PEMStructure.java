
package ch.ethz.ssh2.crypto;

/**
 * Parsed PEM structure.
 * 
 * @author Christian Plattner
 * @version 2.50, 03/15/10
 */

public class PEMStructure
{
	int pemType;
	String dekInfo[];
	String procType[];
	byte[] data;
}