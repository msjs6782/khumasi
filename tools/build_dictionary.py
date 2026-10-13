#!/usr/bin/env python3
"""Builds Khumasi's word lists and checks every curated solution.

Usage:
  python3 -I tools/build_dictionary.py --ayaspell path/to/ar.dic [--wordfreq path/to/large_ar.msgpack.gz]

Inputs (in dictionary/):
  solutions_curated.tsv   hand-picked solutions: word<TAB>category<TAB>meaning
  guesses_extra.txt       extra accepted words (common particles etc.), one per line
  blocklist.txt           words that may never be a solution or guess

Outputs:
  app/src/main/assets/dict/guesses5.txt     accepted guesses (sorted, normalized)
  app/src/main/assets/dict/solutions5.tsv   normalized<TAB>display<TAB>category<TAB>difficulty<TAB>meaning
  app/src/test/resources/normalization_vectors.tsv  input<TAB>expected (shared with Kotlin tests)
  dictionary/REPORT.md                      counts and every rejected solution with the reason

wordfreq is used only as a private frequency check while curating. Its author asks that its data
not be redistributed as plain word lists, so nothing from it is written except a 1-3 difficulty
label on words we chose ourselves.
"""
import argparse
import collections
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from arabic_norm import normalize, is_clean  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LENGTH = 5

# Ayaspell sections excluded from accepted guesses:
#   stopwords.dic / tools ("2"): particles fused with prefixes and pronouns (e.g. فبذلك)
#   names.dic sections and Condidate3.4.dic: proper names (people, countries, cities)
EXCLUDED_SECTIONS = {
    'stopwords.dic', '2', 'أسماء\t3', 'القارات', 'البلدان', 'العواصم',
    'اسماء،أشخاص', 'اﻷسماء\t4', 'تأليف\t5', 'Condidate3.4.dic',
}

# Zipf thresholds (log10 frequency per billion words) for curated solutions.
ZIPF_REJECT = 2.5     # too rare to be a fair everyday solution
ZIPF_EASY = 4.2
ZIPF_MEDIUM = 3.5

GUESS_HEADER = """# Khumasi accepted guesses, {n} words of {length} letters, normalized (see docs/ARABIC_RULES.md).
# Derived from the Ayaspell Arabic dictionary (hunspell-ar), (c) 2006-2008 Mohamed Kebdani,
# 2006-2009 Taha Zerrouki and contributors, https://ayaspell.sourceforge.net/
# Used under the Mozilla Public License 1.1 (Ayaspell is GPL 2.0 / LGPL 2.1 / MPL 1.1 tri-licensed).
# This file is a modified form: inflected stems only, normalized, filtered to {length} letters,
# with proper-name and particle sections removed. It is distributed under MPL 1.1.
"""


def read_lines(path):
    if not os.path.exists(path):
        return []
    out = []
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if not line.strip() or line.lstrip().startswith('#'):
                continue
            out.append(line)
    return out


# Ayaspell suffix flags made of two capital letters add inflections (ة، ات، ان، ين، ون، ي النسبة...).
# Flags with lowercase letters add attached pronouns (ه، ها، كم...) and prefixes are all clitics
# (و، ف، ب، ل، ك، ال، س); both are excluded by the published policy. JA only adds the accusative
# tanween alif (كتابًا), which is a case ending rather than a separate word.
INFLECTION_FLAG = re.compile(r'^[A-Z]{2}$')
SKIPPED_INFLECTION_FLAGS = {'JA'}


def parse_affixes(aff_path):
    aliases = [None]  # AF aliases are numbered from 1
    rules = collections.defaultdict(list)
    with open(aff_path, encoding='utf-8') as f:
        for line in f:
            parts = line.split()
            if not parts:
                continue
            if parts[0] == 'AF' and len(parts) >= 2 and not parts[1].isdigit():
                aliases.append(parts[1])
            elif parts[0] == 'SFX' and len(parts) >= 5:
                flag, strip, add, cond = parts[1], parts[2], parts[3], parts[4]
                strip = '' if strip == '0' else strip
                add = add.split('/')[0]
                add = '' if add == '0' else add
                rules[flag].append((strip, add, re.compile('(?:' + cond + ')$')))
    return aliases, rules


def split_flags(field, aliases):
    if field.isdigit():
        idx = int(field)
        field = aliases[idx] if 0 < idx < len(aliases) else ''
    return [field[i:i + 2] for i in range(0, len(field) - 1, 2)]


def inflect(stem, flags, rules):
    yield stem
    for flag in flags:
        if not INFLECTION_FLAG.match(flag) or flag in SKIPPED_INFLECTION_FLAGS:
            continue
        for strip, add, cond in rules.get(flag, ()):
            if cond.search(stem) and stem.endswith(strip):
                yield stem[:len(stem) - len(strip)] + add


def ayaspell_words(dic_path):
    """Returns {normalized_word: section} for the stems and their inflections (no clitics)."""
    aliases, rules = parse_affixes(os.path.splitext(dic_path)[0] + '.aff')
    words = {}
    section = None
    with open(dic_path, encoding='utf-8') as f:
        for i, line in enumerate(f):
            if i == 0:
                continue  # entry count
            line = line.rstrip('\n')
            if line.startswith('#'):
                title = line.strip('#').strip()
                if title:
                    section = title
                continue
            if section in EXCLUDED_SECTIONS:
                continue
            entry = line.split('\t')[0].split(' ')[0].strip()
            stem, _, flag_field = entry.partition('/')
            if not stem:
                continue
            for form in inflect(stem, split_flags(flag_field, aliases), rules):
                word = normalize(form)
                if is_clean(word):
                    words.setdefault(word, section)
    return words


def load_zipf(path):
    import wordfreq_reader
    _, zipf = wordfreq_reader.load(path)
    best = {}
    for w, z in zipf.items():
        n = normalize(w)
        if is_clean(n) and z > best.get(n, -1):
            best[n] = z
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--ayaspell', required=True)
    ap.add_argument('--wordfreq')
    args = ap.parse_args()

    dict_dir = os.path.join(ROOT, 'dictionary')
    blocklist = {normalize(w.strip()) for w in read_lines(os.path.join(dict_dir, 'blocklist.txt'))}
    extra = {normalize(w.strip()) for w in read_lines(os.path.join(dict_dir, 'guesses_extra.txt'))}

    aya = ayaspell_words(args.ayaspell)
    zipf = load_zipf(args.wordfreq) if args.wordfreq else {}

    guesses = {w for w in aya if len(w) == LENGTH}
    guesses |= {w for w in extra if len(w) == LENGTH and is_clean(w)}
    guesses -= blocklist

    accepted, rejected, seen = [], [], {}
    for lineno, line in enumerate(read_lines(os.path.join(dict_dir, 'solutions_curated.tsv')), 1):
        parts = line.split('\t')
        if len(parts) != 3:
            rejected.append((line, 'صيغة السطر غير صحيحة'))
            continue
        display, category, meaning = (p.strip() for p in parts)
        word = normalize(display)
        z = zipf.get(word)
        if not is_clean(word):
            reason = 'تحتوي حروفًا خارج الحروف الـ33'
        elif len(word) != LENGTH:
            reason = f'طولها بعد التطبيع {len(word)}'
        elif word in seen:
            reason = f'مكررة (تطابق «{seen[word]}»)'
        elif word in blocklist:
            reason = 'في قائمة الاستبعاد'
        elif word not in aya and word not in extra:
            reason = 'غير موجودة في Ayaspell ولا في القائمة الإضافية المراجعة'
        elif zipf and (z is None or z < ZIPF_REJECT):
            reason = f'نادرة الاستعمال (zipf={z if z is not None else 0:.2f})'
        else:
            reason = None
        if reason:
            rejected.append((display, reason))
            continue
        seen[word] = display
        if not zipf:
            difficulty = 2
        elif z >= ZIPF_EASY:
            difficulty = 1
        elif z >= ZIPF_MEDIUM:
            difficulty = 2
        else:
            difficulty = 3
        accepted.append((word, display, category, difficulty, meaning, z))

    # Every solution must also be an accepted guess.
    guesses |= {a[0] for a in accepted}

    assets = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'dict')
    os.makedirs(assets, exist_ok=True)
    with open(os.path.join(assets, f'guesses{LENGTH}.txt'), 'w', encoding='utf-8') as f:
        f.write(GUESS_HEADER.format(n=len(guesses), length=LENGTH))
        for w in sorted(guesses):
            f.write(w + '\n')
    with open(os.path.join(assets, f'solutions{LENGTH}.tsv'), 'w', encoding='utf-8') as f:
        f.write('# normalized\tdisplay\tcategory\tdifficulty(1-3)\tmeaning — hand-curated for Khumasi\n')
        for word, display, category, difficulty, meaning, _ in accepted:
            f.write(f'{word}\t{display}\t{category}\t{difficulty}\t{meaning}\n')

    # Shared normalization vectors for the Kotlin tests.
    vectors = [
        ('مَدْرَسَةٌ', 'مدرسة'), ('أَسْئِلَة', 'اسئلة'), ('إِبْريق', 'ابريق'), ('آلات', 'الات'),
        ('ٱلرحمن', 'الرحمن'), ('كتـــاب', 'كتاب'), ('مؤتمر', 'مؤتمر'), ('لؤلؤة', 'لؤلؤة'),
        ('منحنى', 'منحنى'), ('ضاحية', 'ضاحية'), ('ﻷمر', 'لامر'), ('ﻻزم', 'لازم'),
        ('قُرْآن', 'قران'), ('سَمَاء', 'سماء'), ('مئذنة', 'مئذنة'), ('أ', 'ا'),
        ('ئ', 'ئ'), ('ب‌ب', 'بب'), (' تفاحة ', 'تفاحة'), ('ـ', ''),
    ]
    test_res = os.path.join(ROOT, 'app', 'src', 'test', 'resources')
    os.makedirs(test_res, exist_ok=True)
    with open(os.path.join(test_res, 'normalization_vectors.tsv'), 'w', encoding='utf-8') as f:
        for raw, expected in vectors:
            got = normalize(raw)
            assert got == expected, (raw, got, expected)
            f.write(f'{raw}\t{expected}\n')

    by_cat = collections.Counter(a[2] for a in accepted)
    by_diff = collections.Counter(a[3] for a in accepted)
    with open(os.path.join(dict_dir, 'REPORT.md'), 'w', encoding='utf-8') as f:
        f.write('# تقرير بناء القاموس\n\n')
        f.write(f'- التخمينات المقبولة ({LENGTH} أحرف): **{len(guesses):,}** كلمة\n')
        f.write(f'- الحلول المعتمدة: **{len(accepted)}** كلمة\n')
        f.write(f'- الحلول المرفوضة عند الفحص: **{len(rejected)}**\n\n')
        f.write('## الحلول حسب التصنيف\n\n')
        for cat, n in by_cat.most_common():
            f.write(f'- {cat}: {n}\n')
        f.write('\n## الحلول حسب الصعوبة\n\n')
        for d in (1, 2, 3):
            f.write(f'- {d}: {by_diff.get(d, 0)}\n')
        f.write('\n## الكلمات المرفوضة\n\n')
        for display, reason in rejected:
            f.write(f'- {display}: {reason}\n')

    print(f'guesses={len(guesses)} solutions={len(accepted)} rejected={len(rejected)}')
    for display, reason in rejected:
        print('  REJECT', display, '—', reason)
    print('difficulty', dict(sorted(by_diff.items())), 'categories', dict(by_cat))


if __name__ == '__main__':
    main()
