import argparse
import json
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('desktop', type=Path)
    parser.add_argument('fixtures', type=Path)
    args = parser.parse_args()
    source = args.desktop / 'src-tauri/src'
    with tempfile.TemporaryDirectory(prefix='nakuru-aligner-') as temporary:
        root = Path(temporary)
        (root / 'src').mkdir()
        (root / 'Cargo.toml').write_text('''[package]
name = "nakuru-aligner-reference"
version = "0.1.0"
edition = "2024"
[dependencies]
regex = "1"
unicode-normalization = "0.1"
serde = { version = "1", features = ["derive"] }
serde_json = "1"
''')
        (root / 'src/aligner.rs').write_text((source / 'aligner.rs').read_text())
        sasayaki = (source / 'sasayaki.rs').read_text()
        library = (source / 'library.rs').read_text()
        (root / 'src/library.rs').write_text('use std::sync::LazyLock;\nuse regex::Regex;\n' +
            library[library.index('static RE_BODY:'):library.index('pub fn count_chars')])
        (root / 'src/sasayaki.rs').write_text('use std::sync::LazyLock;\nuse regex::Regex;\nuse serde::{Serialize, Deserialize};\nuse crate::library;\n' +
            sasayaki[sasayaki.rindex('#[derive', 0, sasayaki.index('pub struct SasayakiMatch {')):sasayaki.index('#[derive(Serialize)]\npub struct AudioChapter')] +
            sasayaki[sasayaki.index('pub(crate) fn find_text'):sasayaki.index('fn find_unique')] +
            sasayaki[sasayaki.index('pub struct Chapter'):sasayaki.index('pub fn build_source')].replace('fn flatten(', 'pub fn flatten('))
        (root / 'src/main.rs').write_text('''mod aligner;
mod sasayaki;
mod library;
use std::io::{self, Read};
fn main() {
    let mut input = String::new();
    io::stdin().read_to_string(&mut input).unwrap();
    let mut cases: serde_json::Value = serde_json::from_str(&input).unwrap();
    for case in cases.as_array_mut().unwrap() {
        let mut source = sasayaki::Source { text: vec![], sentence_ends: vec![], segment_ends: vec![], chapters: vec![], images: vec![] };
        for (index, html) in case["chapters"].as_array().unwrap().iter().enumerate() {
            let (text, sentences, segments) = sasayaki::flatten(html.as_str().unwrap());
            source.chapters.push(sasayaki::Chapter { chapter_index: index, start: source.text.len(), length: text.len() });
            source.text.extend(text);
            source.sentence_ends.extend(sentences);
            source.segment_ends.extend(segments);
        }
        let tokens: Vec<sasayaki::SasayakiToken> = serde_json::from_value(case["tokens"].clone()).unwrap();
        case["expected"] = serde_json::to_value(aligner::align(&source, &tokens)).unwrap();
    }
    println!("{}", serde_json::to_string_pretty(&cases).unwrap());
}
''')
        result = subprocess.run([str(Path.home() / '.cargo/bin/cargo'), 'run', '--quiet', '--manifest-path', str(root / 'Cargo.toml')],
                                input=args.fixtures.read_text(), text=True, stdout=subprocess.PIPE, check=True)
        args.fixtures.write_text(result.stdout)


if __name__ == '__main__':
    main()
