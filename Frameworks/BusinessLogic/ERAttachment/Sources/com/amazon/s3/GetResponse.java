//  This software code is made available "AS IS" without warranties of any
//  kind.  You may copy, display, modify and redistribute the software
//  code either by itself or as incorporated into your code; provided that
//  you do not remove any proprietary notices.  Your use of this software
//  code is at your own risk and you waive any claim against Amazon
//  Digital Services, Inc. or its affiliates with respect to your use of
//  this software code. (c) 2006 Amazon Digital Services, Inc. or its
//  affiliates.

package com.amazon.s3;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A Response object returned from AWSAuthConnection.get(). Exposes the
 * attribute object, which represents the retrieved object.
 */
public class GetResponse extends Response {
	public S3Object object;

	/**
	 * Pulls a representation of an S3Object out of the HttpURLConnection
	 * response.
	 */
	public GetResponse(HttpURLConnection connection) throws IOException {
		super(connection);
		if (connection.getResponseCode() < 400) {
			Map<String, List<String>> metadata = extractMetadata(connection);
			byte[] body = slurpInputStream(connection.getInputStream());
			object = new S3Object(body, metadata);
		}
	}

	/**
	 * Examines the response's header fields and returns a Map from String to
	 * List of Strings representing the object's metadata.
	 */
	private Map<String, List<String>> extractMetadata(HttpURLConnection connection) {
		TreeMap<String, List<String>> metadata = new TreeMap<>();
		Map<String, List<String>> headers = connection.getHeaderFields();
		for (String key : headers.keySet()) {
			if (key == null)
				continue;
			metadata.put(key, headers.get(key));
		}
		return metadata;
	}

	/**
	 * Read the input stream and dump it all into a big byte array
	 */
	static byte[] slurpInputStream(InputStream stream) throws IOException {
		final int chunkSize = 2048;
		byte[] buf = new byte[chunkSize];
		ByteArrayOutputStream byteStream = new ByteArrayOutputStream(chunkSize);
		int count;

		while ((count = stream.read(buf)) != -1)
			byteStream.write(buf, 0, count);

		return byteStream.toByteArray();
	}
}
