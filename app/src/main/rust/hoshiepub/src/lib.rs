use std::path::PathBuf;
use std::sync::Arc;

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum EpubError {
    #[error("IO error: {msg}")]
    Io { msg: String },
    #[error("Parse error: {msg}")]
    Parse { msg: String },
    #[error("Spine index {index} out of range")]
    SpineIndexOutOfRange { index: u32 },
    #[error("Manifest item not found for idref: {idref}")]
    ManifestItemNotFound { idref: String },
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ManifestItem {
    pub id: String,
    pub href: String,
    pub media_type: String,
    pub properties: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SpineItem {
    pub idref: String,
    pub linear: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct TocNode {
    pub label: String,
    pub href: Option<String>,
    pub children: Vec<TocNode>,
}

#[derive(uniffi::Object)]
pub struct EpubBook {
    epub: rbook::Epub,
    root_dir: PathBuf,
    content_dir: PathBuf,
}

#[uniffi::export]
pub fn parse_extracted_epub(root_dir: String) -> Result<Arc<EpubBook>, EpubError> {
    let root = PathBuf::from(&root_dir);
    let epub = rbook::Epub::open(&root).map_err(|e| EpubError::Parse { msg: e.to_string() })?;
    let pkg_dir = epub.package().directory().decode();
    let content_dir = root.join(pkg_dir.trim_start_matches('/'));

    Ok(Arc::new(EpubBook {
        epub,
        root_dir: root,
        content_dir,
    }))
}

#[uniffi::export]
impl EpubBook {
    pub fn root_dir(&self) -> String {
        self.root_dir.to_string_lossy().to_string()
    }

    pub fn content_dir(&self) -> String {
        self.content_dir.to_string_lossy().to_string()
    }

    pub fn title(&self) -> Option<String> {
        self.epub.metadata().title().map(|t| t.value().to_string())
    }

    pub fn language(&self) -> Option<String> {
        self.epub
            .metadata()
            .language()
            .map(|l| l.value().to_string())
    }

    pub fn cover_href(&self) -> Option<String> {
        self.epub
            .manifest()
            .cover_image()
            .map(|e| e.href_raw().to_string())
    }

    pub fn manifest(&self) -> Vec<ManifestItem> {
        self.epub
            .manifest()
            .iter()
            .map(|entry| {
                let props = entry.properties().to_string();
                ManifestItem {
                    id: entry.id().to_string(),
                    href: entry.href_raw().to_string(),
                    media_type: entry.media_type().to_string(),
                    properties: if props.is_empty() { None } else { Some(props) },
                }
            })
            .collect()
    }

    pub fn spine(&self) -> Vec<SpineItem> {
        self.epub
            .spine()
            .iter()
            .map(|entry| SpineItem {
                idref: entry.idref().to_string(),
                linear: entry.is_linear(),
            })
            .collect()
    }

    pub fn toc(&self) -> TocNode {
        let children = match self.epub.toc().contents() {
            Some(root) => root.iter().map(|e| Self::convert_toc_entry(&e)).collect(),
            None => vec![],
        };
        TocNode {
            label: "".to_string(),
            href: None,
            children,
        }
    }

    pub fn chapter_absolute_path(&self, spine_index: u32) -> Result<Option<String>, EpubError> {
        let spine_entry = self
            .epub
            .spine()
            .get(spine_index as usize)
            .ok_or(EpubError::SpineIndexOutOfRange { index: spine_index })?;

        let Some(manifest_entry) = self.epub.manifest().by_id(spine_entry.idref()) else {
            return Ok(None);
        };

        let path = self.content_dir.join(manifest_entry.href_raw().to_string());
        Ok(Some(path.to_string_lossy().to_string()))
    }

    pub fn read_spine_item_text(&self, spine_index: u32) -> Result<String, EpubError> {
        let spine_entry = self
            .epub
            .spine()
            .get(spine_index as usize)
            .ok_or(EpubError::SpineIndexOutOfRange { index: spine_index })?;

        let idref = spine_entry.idref();
        let manifest_entry =
            self.epub
                .manifest()
                .by_id(idref)
                .ok_or_else(|| EpubError::ManifestItemNotFound {
                    idref: idref.to_string(),
                })?;

        let path = self.content_dir.join(manifest_entry.href_raw().to_string());
        std::fs::read_to_string(&path).map_err(|e| EpubError::Io {
            msg: format!("{}: {e}", path.display()),
        })
    }
}

impl EpubBook {
    fn convert_toc_entry(entry: &rbook::epub::toc::EpubTocEntry<'_>) -> TocNode {
        let children = entry.iter().map(|e| Self::convert_toc_entry(&e)).collect();
        TocNode {
            label: entry.label().to_string(),
            href: entry.href().map(|h| h.to_string()),
            children,
        }
    }
}
