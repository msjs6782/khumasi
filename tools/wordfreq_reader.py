"""Reads wordfreq cBpack files (msgpack). Internal curation check only; wordfreq data is not redistributed."""
import gzip, struct, sys
def unpack(b, i=0):
    t = b[i]
    if t <= 0x7f: return t, i+1
    if 0x80 <= t <= 0x8f:
        n = t & 0x0f; i += 1; d = {}
        for _ in range(n):
            k, i = unpack(b, i); v, i = unpack(b, i); d[k] = v
        return d, i
    if 0x90 <= t <= 0x9f:
        n = t & 0x0f; i += 1; a = []
        for _ in range(n):
            v, i = unpack(b, i); a.append(v)
        return a, i
    if 0xa0 <= t <= 0xbf:
        n = t & 0x1f; return b[i+1:i+1+n].decode('utf-8'), i+1+n
    if t == 0xc0: return None, i+1
    if t == 0xc2: return False, i+1
    if t == 0xc3: return True, i+1
    if t == 0xd9: n = b[i+1]; return b[i+2:i+2+n].decode('utf-8'), i+2+n
    if t == 0xda: n = struct.unpack('>H', b[i+1:i+3])[0]; return b[i+3:i+3+n].decode('utf-8'), i+3+n
    if t == 0xdc:
        n = struct.unpack('>H', b[i+1:i+3])[0]; i += 3; a = []
        for _ in range(n):
            v, i = unpack(b, i); a.append(v)
        return a, i
    if t == 0xdd:
        n = struct.unpack('>I', b[i+1:i+5])[0]; i += 5; a = []
        for _ in range(n):
            v, i = unpack(b, i); a.append(v)
        return a, i
    if t == 0xcc: return b[i+1], i+2
    if t == 0xcd: return struct.unpack('>H', b[i+1:i+3])[0], i+3
    if t == 0xce: return struct.unpack('>I', b[i+1:i+5])[0], i+5
    raise ValueError(hex(t))
def load(path):
    data = gzip.open(path).read()
    obj, _ = unpack(data)
    header, bins = obj[0], obj[1:]
    zipf = {}
    for cb, words in enumerate(bins):
        z = 9 - cb / 100.0   # Zipf = log10(freq per billion words)
        for w in words:
            if w not in zipf: zipf[w] = z
    return header, zipf
