/*******************************************************************************
 * Copyright (c) 2004, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.internal.ui.mapping;

import java.io.*;
import java.util.ArrayList;
import org.eclipse.compare.rangedifferencer.IRangeComparator;
import org.eclipse.core.resources.IEncodedStorage;
import org.eclipse.core.resources.IStorage;
import org.eclipse.core.runtime.CoreException;

/**
 * This implementation of IRangeComparator breaks an input stream into lines.
 * Copied from org.eclipse.compare.internal.merge.LineComparator 1.4 and
 * modified for {@link IStorage}.
 */
class LineComparator implements IRangeComparator {

    private String[] fLines;

    /*
     * An input stream reader that detects a trailing LF in the wrapped stream.
     */
    private static class TrailingLineFeedDetector extends FilterInputStream {

    	boolean trailingLF = false;
    	
		protected TrailingLineFeedDetector(InputStream in) {
			super(in);
		}
		
		public int read() throws IOException {
			int c = super.read();
			trailingLF = isLineFeed(c);
			return c;
		}
		
	    /*
	     * We don't need to override read(byte[] buffer) as the javadoc of 
	     * FilterInputStream states that it will call read(byte[] buffer, int off, int len)
	     */
		public int read(byte[] buffer, int off, int len) throws IOException {
			int length = super.read(buffer, off, len);
			if (length != -1) {
				int index = off + length - 1;
				if (index >= buffer.length)
					index = buffer.length - 1;
				trailingLF = isLineFeed(buffer[index]);
			}
			return length;
		}

		private boolean isLineFeed(int c) {
			return c != -1 && c == '\n';
		}
		
		public boolean hadTrailingLineFeed() {
			return trailingLF;
		}
    	
    }
    
    public static LineComparator create(IStorage storage, String outputEncoding) throws CoreException, IOException {
    	InputStream is = new BufferedInputStream(storage.getContents());
    	try {
			String encoding = getEncoding(storage, outputEncoding);
			return new LineComparator(is, encoding);
		} finally {
			try {
				is.close();
			} catch (IOException e) {
				// Ignore
			}
		}
    }
    
	private static String getEncoding(IStorage storage, String outputEncoding) throws CoreException {
		if (storage instanceof IEncodedStorage) {
			IEncodedStorage es = (IEncodedStorage) storage;
			String charset = es.getCharset();
			if (charset != null)
				return charset;
		}
		return outputEncoding;
	}
	
    public LineComparator(InputStream is, String encoding) throws IOException {
        
        TrailingLineFeedDetector trailingLineFeedDetector = new TrailingLineFeedDetector(is);
		BufferedReader br = new BufferedReader(new InputStreamReader(trailingLineFeedDetector, encoding));
        String line;
        ArrayList ar = new ArrayList();
        while ((line = br.readLine()) != null) {
            ar.add(line);
        }
        try {
            is.close();
        } catch (IOException e1) {
        }
        // Add a trailing line if the last character in the file was a line feed.
        // We do this because a BufferedReader doesn't distinguish the case
        // where the last line has or doesn't have a trailing line separator
        if (trailingLineFeedDetector.hadTrailingLineFeed()) {
        	ar.add(""); //$NON-NLS-1$
        }
        fLines = (String[]) ar.toArray(new String[ar.size()]);
    }

	String getLine(int ix) {
        return fLines[ix];
    }

    /* (non-Javadoc)
     * @see org.eclipse.compare.rangedifferencer.IRangeComparator#getRangeCount()
     */
    public int getRangeCount() {
        return fLines.length;
    }

    /* (non-Javadoc)
     * @see org.eclipse.compare.rangedifferencer.IRangeComparator#rangesEqual(int, org.eclipse.compare.rangedifferencer.IRangeComparator, int)
     */
    public boolean rangesEqual(int thisIndex, IRangeComparator other,
            int otherIndex) {
        String s1 = fLines[thisIndex];
        String s2 = ((LineComparator) other).fLines[otherIndex];
        return s1.equals(s2);
    }

    /* (non-Javadoc)
     * @see org.eclipse.compare.rangedifferencer.IRangeComparator#skipRangeComparison(int, int, org.eclipse.compare.rangedifferencer.IRangeComparator)
     */
    public boolean skipRangeComparison(int length, int maxLength, IRangeComparator other) {
        return false;
    }
}
