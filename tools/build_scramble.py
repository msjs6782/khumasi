#!/usr/bin/env python3
"""
Builds app/src/main/assets/dict/scramble.tsv for «الكلمة المبعثرة».

  python3 -I tools/build_scramble.py --ayaspell path/to/ar.dic --wordfreq path/to/large_ar.msgpack.gz

Each puzzle word must exist in Ayaspell (stems and inflections, as for Khumasi). For every word the
tool lists the other dictionary words made of exactly the same letters, so the game also accepts
them, but only when they are in common use (wordfreq is used here only to measure that and is not
shipped). Five-letter puzzles come from the curated Khumasi solutions (easy and medium only).
"""
import argparse
import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_dictionary as bd  # noqa: E402
from arabic_norm import normalize, is_clean  # noqa: E402

ALT_MIN_ZIPF = 3.0  # roughly: seen at least once per million words

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--ayaspell', required=True)
    ap.add_argument('--wordfreq', required=True)
    args = ap.parse_args()

    aya = bd.ayaspell_words(args.ayaspell)
    zipf = bd.load_zipf(args.wordfreq)
    dict_dir = os.path.join(ROOT, 'dictionary')
    blocklist = {normalize(w.strip()) for w in bd.read_lines(os.path.join(dict_dir, 'blocklist.txt'))}
    by_letters = collections.defaultdict(set)
    for w in aya:
        if 3 <= len(w) <= 7 and w not in blocklist and zipf.get(w, 0) >= ALT_MIN_ZIPF:
            by_letters[''.join(sorted(w))].add(w)

    rows, rejected, seen = [], [], set()
    for line in bd.read_lines(os.path.join(dict_dir, 'scramble_curated.tsv')):
        display, category = (p.strip() for p in line.split('\t'))
        rows.append((display, category, None))
    with open(os.path.join(ROOT, 'app/src/main/assets/dict/solutions5.tsv'), encoding='utf-8') as f:
        for line in f:
            if line.startswith('#') or not line.strip():
                continue
            word, display, category, difficulty, _ = line.rstrip('\n').split('\t')
            if int(difficulty) <= 2:
                rows.append((display, category, word))

    out = []
    for display, category, known in rows:
        word = known or normalize(display)
        if not is_clean(word) or not 3 <= len(word) <= 7:
            rejected.append((display, f'طول {len(word)} أو حروف غير صالحة'))
        elif word in seen:
            rejected.append((display, 'مكررة'))
        elif known is None and word not in aya:
            rejected.append((display, 'غير موجودة في Ayaspell'))
        elif len(set(word)) == 1:
            rejected.append((display, 'لا يمكن بعثرتها'))
        else:
            seen.add(word)
            alts = sorted(by_letters[''.join(sorted(word))] - {word})
            out.append((word, display, category, alts))

    path = os.path.join(ROOT, 'app/src/main/assets/dict/scramble.tsv')
    with open(path, 'w', encoding='utf-8') as f:
        f.write('# word\tdisplay\tcategory\tother words with the same letters (also accepted)\n')
        f.write('# Built by tools/build_scramble.py from dictionary/scramble_curated.tsv and solutions5.tsv;\n')
        f.write('# checked against Ayaspell (MPL 1.1, see docs/DICTIONARY.md).\n')
        for word, display, category, alts in out:
            f.write(f'{word}\t{display}\t{category}\t{",".join(alts)}\n')
    counts = collections.Counter(len(w) for w, *_ in out)
    print('written', len(out), dict(sorted(counts.items())))
    for d, r in rejected:
        print('REJECTED', d, r)


if __name__ == '__main__':
    main()
