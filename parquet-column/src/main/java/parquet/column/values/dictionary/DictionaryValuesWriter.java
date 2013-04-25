package parquet.column.values.dictionary;

import static parquet.Log.DEBUG;
import static parquet.column.Encoding.PLAIN_DICTIONARY;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import parquet.Log;
import parquet.bytes.BytesInput;
import parquet.bytes.CapacityByteArrayOutputStream;
import parquet.bytes.LittleEndianDataOutputStream;
import parquet.column.Encoding;
import parquet.column.page.DictionaryPage;
import parquet.column.values.ValuesWriter;
import parquet.column.values.plain.PlainValuesWriter;
import parquet.io.ParquetEncodingException;
import parquet.io.api.Binary;

public class DictionaryValuesWriter extends ValuesWriter {
  private static final Log LOG = Log.getLog(DictionaryValuesWriter.class);

  /**
   * maximum size in bytes allowed for the dictionary
   * will fail over to plain encoding if reached
   */
  private final int maxDictionaryByteSize;

  /**
   * contains the values encoded in plain if the dictionary grows too big
   */
  private final PlainValuesWriter plainValuesWriter;

  /**
   * will become true if the dictionary becomes too big
   */
  private boolean dictionaryTooBig;

  /**
   * current size in bytes the dictionary will take once serialized
   */
  private int dictionaryByteSize;

  /**
   * size in bytes of the dictionary at the end of last dictionary encoded page (in case the current page falls back to PLAIN)
   */
  private int lastUsedDictionaryByteSize;

  /**
   * size in items of the dictionary at the end of last dictionary encoded page (in case the current page falls back to PLAIN)
   */
  private int lastUsedDictionarySize;

  /**
   * dictionary
   */
  private Map<Binary, Integer> dict;

  /**
   * dictionary encoded values
   */
  private CapacityByteArrayOutputStream out = new CapacityByteArrayOutputStream(32 * 1024); // TODO: initial size

  public DictionaryValuesWriter(int maxDictionaryByteSize, int initialSize) {
    this.maxDictionaryByteSize = maxDictionaryByteSize;
    this.plainValuesWriter = new PlainValuesWriter(initialSize);
    resetDictionary();
  }

  @Override
  public void writeBytes(Binary v) {
    if (!dictionaryTooBig) {
      writeBytesUsingDict(v);
      if (dictionaryByteSize > maxDictionaryByteSize || dict.size() > 65535 /* 2^16 - 1 */) {
        // if the dictionary reach the max byte size or the values can not be encoded on two bytes anymore.
        if (DEBUG) LOG.debug("dictionary is now too big, falling back to plain: " + dictionaryByteSize + "B and " + dict.size() + " entries");
        dictionaryTooBig = true;
        if (lastUsedDictionarySize == 0) {
          // if we never used the dictionary
          // we free dictionary encoded data
          dict = null;
          dictionaryByteSize = 0;
          out = null;
        }
      }
    }
    // write also to plain encoding if we need to fall back
    if (DEBUG) LOG.debug("writing in plain encoding");
    plainValuesWriter.writeBytes(v);
  }

  /**
   * will add an entry to the dictionary if the value is new
   * @param v the value to dictionary encode
   */
  private void writeBytesUsingDict(Binary v) {
    if (DEBUG) LOG.debug("writing using dictionary");
    Integer id = dict.get(v);
    if (id == null) {
      id = dict.size();
      dict.put(v, id);
      // length as int (2 bytes) + actual bytes
      dictionaryByteSize += 2 + v.length();
    }
    // we write only two bytes
    out.write((id >>>  0) & 0xFF);
    out.write((id >>>  8) & 0xFF);
  }

  @Override
  public long getBufferedSize() {
    // size that will be written to a page
    // not including the dictionary size
    return dictionaryTooBig ? plainValuesWriter.getBufferedSize() : out.size();
  }

  @Override
  public long getAllocatedSize() {
    // size used in memory
    return (out == null ? 0 : out.getCapacity()) + dictionaryByteSize + plainValuesWriter.getAllocatedSize();
  }

  @Override
  public BytesInput getBytes() {
    if (!dictionaryTooBig && dict.size() > 0) {
      // remember size of dictionary when we last wrote a page
      lastUsedDictionarySize = dict.size();
      lastUsedDictionaryByteSize = dictionaryByteSize;
      return BytesInput.from(out);
    }
    return plainValuesWriter.getBytes();
  }

  @Override
  public Encoding getEncoding() {
    if (!dictionaryTooBig && dict.size() > 0) {
      return PLAIN_DICTIONARY;
    }
    return plainValuesWriter.getEncoding();
  }

  @Override
  public void reset() {
    if (out != null) {
      out.reset();
    }
    plainValuesWriter.reset();
  }

  @Override
  public DictionaryPage createDictionaryPage() {
    if (lastUsedDictionarySize > 0) {
      // return a dictionary only if we actually used it
      try {
        CapacityByteArrayOutputStream dictBuf = new CapacityByteArrayOutputStream(lastUsedDictionaryByteSize);
        LittleEndianDataOutputStream dictOut = new LittleEndianDataOutputStream(dictBuf);
        Iterator<Binary> entryIterator = dict.keySet().iterator();
        // write only the part of the dict that we used
        for (int i = 0; i < lastUsedDictionarySize; i++) {
          Binary entry = entryIterator.next();
          dictOut.writeInt(entry.length());
          entry.writeTo(dictOut);
        }
        return new DictionaryPage(BytesInput.from(dictBuf), lastUsedDictionarySize, PLAIN_DICTIONARY);
      } catch (IOException e) {
        throw new ParquetEncodingException("Could not generate dictionary Page", e);
      }
    }
    return plainValuesWriter.createDictionaryPage();
  }

  @Override
  public void resetDictionary() {
    lastUsedDictionaryByteSize = 0;
    lastUsedDictionarySize = 0;
    dictionaryByteSize = 0;
    dictionaryTooBig = false;
    if (dict == null) {
      dict = new LinkedHashMap<Binary, Integer>();
    } else {
      dict.clear();
    }
  }

  public int getDictionaryByteSize() {
    return dictionaryByteSize;
  }
}
