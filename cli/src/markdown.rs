//! 把手机发来的原始数据（项目树）包装成 Markdown。格式见 `docs/PROTOCOL.md` 第 6 节。
//!
//! 一个顶层项目生成一个文件：顶层项目自己的待办是开头的平铺任务列表，
//! 子项目、孙项目分别用 `##`、`###` 标题分节。「提醒」不写进文件。

use serde::Deserialize;

/// 项目最多几层（顶层项目算第 1 层）。
pub const MAX_DEPTH: usize = 3;

/// 请求体里的一个项目节点。
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct Project {
    pub name: String,
    /// 待办正文（可以多行），按 App 里的顺序。
    pub todos: Vec<String>,
    /// 只给人看的提醒，不写进文件，只在终端里显示。
    pub notes: Vec<String>,
    pub children: Vec<Project>,
}

/// 一条提醒，带上它所在项目的路径（从顶层开始）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Note {
    pub path: Vec<String>,
    pub content: String,
}

/// 一个顶层项目渲染出的文件内容。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rendered {
    /// 顶层项目名（已规范化成单行）。
    pub project: String,
    pub markdown: String,
    /// 写进文件的待办条数（空白正文不算）。
    pub count: usize,
}

/// 项目树的层数（叶子算 1 层）。
pub fn depth(p: &Project) -> usize {
    1 + p.children.iter().map(depth).max().unwrap_or(0)
}

/// 项目名规范化：换行换成空格、去掉首尾空白；为空时用「未命名」。
pub fn project_name(name: &str) -> String {
    let joined = name.split(['\r', '\n']).map(str::trim).filter(|s| !s.is_empty()).collect::<Vec<_>>().join(" ");
    if joined.is_empty() { "未命名".to_string() } else { joined }
}

/// 渲染一个顶层项目。项目和子项目里都没有有效待办时返回 `None`（不生成文件）。
pub fn render(project: &Project) -> Option<Rendered> {
    let mut out = String::new();
    let count = render_into(project, 1, &mut out);
    (count > 0).then(|| Rendered { project: project_name(&project.name), markdown: out, count })
}

/// 把 `p` 的待办和有内容的子项目写进 `out`，返回写入的待办条数。
fn render_into(p: &Project, level: usize, out: &mut String) -> usize {
    let mut count = 0;
    for todo in &p.todos {
        if push_item(out, todo) {
            count += 1;
        }
    }
    for child in &p.children {
        let mut section = String::new();
        let n = render_into(child, level + 1, &mut section);
        if n == 0 {
            continue;
        }
        if !out.is_empty() {
            out.push('\n');
        }
        out.push_str(&"#".repeat((level + 1).min(6)));
        out.push(' ');
        out.push_str(&project_name(&child.name));
        out.push_str("\n\n");
        out.push_str(&section);
        count += n;
    }
    count
}

/// 写一条 `- [ ] ` 任务；正文为空白时什么都不写，返回 false。
fn push_item(out: &mut String, content: &str) -> bool {
    let lines = normalize(content);
    let Some((first, rest)) = lines.split_first() else { return false };
    out.push_str("- [ ] ");
    out.push_str(first);
    out.push('\n');
    for line in rest {
        out.push_str("  ");
        out.push_str(line);
        out.push('\n');
    }
    true
}

/// 统一换行符、去掉首尾空白和行尾空白、丢掉空行。
pub fn normalize(content: &str) -> Vec<&str> {
    content.trim().split(['\n', '\r']).map(str::trim_end).filter(|l| !l.trim().is_empty()).collect()
}

/// 按树的顺序收集所有提醒（空白的跳过），多行提醒合成一行。
pub fn collect_notes(projects: &[Project]) -> Vec<Note> {
    fn walk(p: &Project, path: &mut Vec<String>, out: &mut Vec<Note>) {
        path.push(project_name(&p.name));
        for note in &p.notes {
            let lines = normalize(note);
            if !lines.is_empty() {
                out.push(Note { path: path.clone(), content: lines.iter().map(|l| l.trim()).collect::<Vec<_>>().join(" ") });
            }
        }
        for c in &p.children {
            walk(c, path, out);
        }
        path.pop();
    }
    let mut out = Vec::new();
    for p in projects {
        walk(p, &mut Vec::new(), &mut out);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn leaf(name: &str, todos: &[&str]) -> Project {
        Project { name: name.into(), todos: todos.iter().map(|s| s.to_string()).collect(), ..Default::default() }
    }

    fn one(content: &str) -> String {
        render(&leaf("P", &[content])).map(|r| r.markdown).unwrap_or_default()
    }

    #[test]
    fn protocol_example_renders_exactly() {
        let p = leaf("工作", &["写周报", "修复登录页崩溃\n第二行正文缩进续写", "买牛奶"]);
        let r = render(&p).unwrap();
        assert_eq!(r.markdown, "- [ ] 写周报\n- [ ] 修复登录页崩溃\n  第二行正文缩进续写\n- [ ] 买牛奶\n");
        assert_eq!(r.count, 3);
        assert_eq!(r.project, "工作");
    }

    #[test]
    fn project_name_is_not_written_for_top_level() {
        let out = one("写周报");
        assert_eq!(out, "- [ ] 写周报\n");
    }

    #[test]
    fn subprojects_become_headings() {
        let p = Project {
            name: "工作".into(),
            todos: vec!["写周报".into()],
            notes: vec!["周五前交".into()],
            children: vec![
                Project {
                    name: "后端".into(),
                    todos: vec!["接口文档".into()],
                    children: vec![leaf("数据库", &["加索引"])],
                    ..Default::default()
                },
                leaf("空的", &["  \n "]),
                leaf("前端", &["改样式"]),
            ],
        };
        let r = render(&p).unwrap();
        assert_eq!(
            r.markdown,
            "- [ ] 写周报\n\n## 后端\n\n- [ ] 接口文档\n\n### 数据库\n\n- [ ] 加索引\n\n## 前端\n\n- [ ] 改样式\n"
        );
        assert_eq!(r.count, 4);
        assert!(!r.markdown.contains("周五前交"), "提醒不写进文件");
        assert!(!r.markdown.contains("空的"), "没有有效待办的子项目不出标题");
    }

    #[test]
    fn top_level_without_own_todos_starts_with_heading() {
        let p = Project { name: "工作".into(), children: vec![leaf("后端", &["a"])], ..Default::default() };
        assert_eq!(render(&p).unwrap().markdown, "## 后端\n\n- [ ] a\n");
    }

    #[test]
    fn project_without_todos_renders_nothing() {
        let p = Project { name: "P".into(), notes: vec!["只有提醒".into()], ..Default::default() };
        assert_eq!(render(&p), None);
        assert_eq!(render(&leaf("P", &["", " \n\t"])), None);
    }

    #[test]
    fn continuation_lines_are_indented_two_spaces() {
        assert_eq!(one("first\nsecond\nthird"), "- [ ] first\n  second\n  third\n");
        // 续行原有的前导空白保留，再整体加 2 格缩进
        assert_eq!(one("a\n  b"), "- [ ] a\n    b\n");
    }

    #[test]
    fn content_is_normalized() {
        assert_eq!(one("a\r\nb\rc\r\n"), "- [ ] a\n  b\n  c\n");
        assert_eq!(one("  \n\n\t hello \t\n  \n"), "- [ ] hello\n");
        assert_eq!(one("first   \nsecond\t \nthird "), "- [ ] first\n  second\n  third\n");
        assert_eq!(one("a\n\n   \n\t\nb\r\n\r\nc"), "- [ ] a\n  b\n  c\n");
    }

    #[test]
    fn output_ends_with_exactly_one_newline() {
        let out = render(&leaf("P", &["one\n\n\n", "two\r\n\r\n  \n"])).unwrap().markdown;
        assert_eq!(out, "- [ ] one\n- [ ] two\n");
    }

    #[test]
    fn names_are_single_line() {
        assert_eq!(project_name(" 工作\r\n 周报 "), "工作 周报");
        assert_eq!(project_name(" \n "), "未命名");
        let p = Project { name: "P".into(), children: vec![leaf("子\n项目", &["x"])], ..Default::default() };
        assert_eq!(render(&p).unwrap().markdown, "## 子 项目\n\n- [ ] x\n");
    }

    #[test]
    fn depth_counts_levels() {
        let p = Project {
            children: vec![Project { children: vec![leaf("c", &[])], ..Default::default() }, leaf("b", &[])],
            ..Default::default()
        };
        assert_eq!(depth(&leaf("a", &[])), 1);
        assert_eq!(depth(&p), 3);
    }

    #[test]
    fn notes_are_collected_with_paths() {
        let projects = vec![
            Project {
                name: "工作".into(),
                notes: vec!["周五前交".into(), "  ".into()],
                children: vec![Project { name: "后端".into(), notes: vec!["先对接口\n再写代码".into()], ..Default::default() }],
                ..Default::default()
            },
            Project { name: "生活".into(), notes: vec!["记得交电费".into()], ..Default::default() },
        ];
        let notes = collect_notes(&projects);
        assert_eq!(
            notes,
            vec![
                Note { path: vec!["工作".into()], content: "周五前交".into() },
                Note { path: vec!["工作".into(), "后端".into()], content: "先对接口 再写代码".into() },
                Note { path: vec!["生活".into()], content: "记得交电费".into() },
            ]
        );
    }
}
