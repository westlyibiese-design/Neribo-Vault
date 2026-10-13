package com.westly.neribovault.feature.coder.templates

import com.westly.neribovault.feature.coder.files.CoderStarterFile

/** A starting point for a new project. [id] is saved with the project, so it must never change. */
data class CoderTemplate(
    val id: String,
    val name: String,
    val description: String,
    val files: List<CoderStarterFile>,
)

/** The starter templates, in the order the New project sheet lists them. All of them work offline. */
object CoderTemplates {
    /** Every template. */
    val all: List<CoderTemplate> = listOf(
        CoderTemplate(
            id = "blank",
            name = "Blank page",
            description = "An empty web page with a heading.",
            files = listOf(CoderStarterFile("index.html", BLANK_HTML)),
        ),
        CoderTemplate(
            id = "hello-web",
            name = "My first web page",
            description = "A heading, text, a list and a button, styled with CSS.",
            files = listOf(
                CoderStarterFile("index.html", HELLO_HTML),
                CoderStarterFile("style.css", HELLO_CSS),
            ),
        ),
        CoderTemplate(
            id = "counter",
            name = "Counter button",
            description = "Tap a button and the number goes up. Shows how JavaScript works.",
            files = listOf(
                CoderStarterFile("index.html", COUNTER_HTML),
                CoderStarterFile("style.css", COUNTER_CSS),
                CoderStarterFile("script.js", COUNTER_JS),
            ),
        ),
        CoderTemplate(
            id = "todo",
            name = "To-do list",
            description = "Add and tick off items. The list is remembered on this phone.",
            files = listOf(
                CoderStarterFile("index.html", TODO_HTML),
                CoderStarterFile("style.css", TODO_CSS),
                CoderStarterFile("script.js", TODO_JS),
            ),
        ),
        CoderTemplate(
            id = "notes-md",
            name = "Notes in Markdown",
            description = "A README.md that shows headings, bold text, a list, a link and code.",
            files = listOf(CoderStarterFile("README.md", NOTES_MD)),
        ),
    )

    /** The template with this [id], or null when there is none. */
    fun find(id: String): CoderTemplate? = all.firstOrNull { it.id == id }
}

private const val BLANK_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>My page</title>
  <style>
    /* This asks the browser to use light or dark colors to match the phone. */
    :root { color-scheme: light dark; }
  </style>
</head>
<body>
  <h1>Hello</h1>
</body>
</html>
"""

/** Colors and basic looks shared by the three styled templates. Each template adds its own rules after it. */
private const val BASE_CSS = """/* Colors are kept in variables so light and dark mode can swap them. */
:root {
  --bg: #faf8f4;
  --text: #2a2a28;
  --muted: #66655f;
  --card: #ffffff;
  --line: #e2ded5;
  --accent: #3b6e5a;
  --accent-text: #ffffff;
  color-scheme: light dark;
}

/* When the phone is in dark mode, these colors replace the ones above. */
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #161715;
    --text: #ecebe6;
    --muted: #a5a49d;
    --card: #21201e;
    --line: #34332f;
    --accent: #86c1a8;
    --accent-text: #11201a;
  }
}

/* Makes widths include padding, which is easier to work with. */
* { box-sizing: border-box; }

body {
  margin: 0;
  padding: 24px 16px;
  background: var(--bg);
  color: var(--text);
  font-family: system-ui, sans-serif;
  line-height: 1.6;
}

main {
  max-width: 520px;
  margin: 0 auto;
}

h1 {
  margin: 0 0 12px;
  font-size: 1.6rem;
}

/* One button look, used by the button tag and by links with class="button". */
.button,
button {
  display: inline-block;
  padding: 12px 20px;
  border: none;
  border-radius: 12px;
  background: var(--accent);
  color: var(--accent-text);
  font-size: 1rem;
  text-decoration: none;
}
"""

private const val HELLO_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>My first web page</title>
  <!-- This line loads the colors and spacing from style.css. -->
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <main>
    <!-- h1 is the main heading of the page. -->
    <h1>Hello, world</h1>

    <!-- p is a paragraph of text. -->
    <p>This is my first web page. Change these words, then tap Run to see the result.</p>

    <!-- ul is a list. Each li is one item in it. -->
    <ul>
      <li>HTML gives the page its content.</li>
      <li>CSS gives it colors and layout.</li>
      <li>JavaScript makes it do things.</li>
    </ul>

    <!-- a is a link. The class "button" makes it look like a button. -->
    <a class="button" href="https://developer.mozilla.org/en-US/docs/Learn">Learn more</a>
  </main>
</body>
</html>
"""

private const val HELLO_CSS = BASE_CSS + """
/* Softer color for the body text. */
p,
li {
  color: var(--muted);
}

ul {
  padding-left: 20px;
}

/* Space above the button. */
.button {
  margin-top: 16px;
}
"""

private const val COUNTER_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Counter</title>
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <main class="center">
    <h1>Counter</h1>

    <!-- The number lives inside this tag. The id lets script.js find it. -->
    <p id="count" class="big">0</p>

    <!-- Tap this button to add one. script.js listens for the tap. -->
    <button id="add" type="button">Add one</button>
  </main>

  <!-- This line runs script.js once the page above is ready. -->
  <script src="script.js"></script>
</body>
</html>
"""

private const val COUNTER_CSS = BASE_CSS + """
/* Centers everything inside the main tag. */
.center {
  text-align: center;
}

/* The big number. */
.big {
  margin: 16px 0 24px;
  font-size: 4rem;
  font-weight: 600;
}
"""

private const val COUNTER_JS = """// JavaScript runs in the browser and makes the page react.

// getElementById finds one tag on the page by its id.
var numberText = document.getElementById("count");
var addButton = document.getElementById("add");

// A variable remembers a value. Ours starts at zero.
var count = 0;

// addEventListener says: when this happens, run this function.
// Here "click" means the button was tapped.
addButton.addEventListener("click", function () {
  count = count + 1;
  // textContent is the text shown inside the tag.
  numberText.textContent = count;
});
"""

private const val TODO_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>To-do list</title>
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <main>
    <h1>To-do list</h1>

    <!-- A form with one text box and one button. -->
    <form id="add-form">
      <input id="new-item" type="text" placeholder="What needs doing?" aria-label="New item" autocomplete="off">
      <button type="submit">Add</button>
    </form>

    <!-- script.js fills this list with the items. -->
    <ul id="list"></ul>
    <p id="empty" class="hint">Nothing here yet. Add your first item above.</p>
  </main>

  <script src="script.js"></script>
</body>
</html>
"""

private const val TODO_CSS = BASE_CSS + """
/* The text box and button sit on one line. */
form {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

input[type="text"] {
  flex: 1;
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--card);
  color: var(--text);
  font-size: 1rem;
}

ul {
  margin: 0;
  padding: 0;
  list-style: none;
}

li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  margin-bottom: 8px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--card);
}

li label {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
}

/* A small button for removing an item. */
li button {
  padding: 6px 12px;
  background: transparent;
  color: var(--muted);
  border: 1px solid var(--line);
  font-size: 0.9rem;
}

/* Ticked items get a line through them. */
.done {
  color: var(--muted);
  text-decoration: line-through;
}

.hint {
  color: var(--muted);
}
"""

private const val TODO_JS = """// This script keeps a list of to-do items and remembers it on this phone.

var STORAGE_KEY = "coder-todo-items";

// getElementById finds one tag on the page by its id.
var form = document.getElementById("add-form");
var input = document.getElementById("new-item");
var list = document.getElementById("list");
var hint = document.getElementById("empty");

// localStorage keeps small pieces of text in the browser.
// It can fail, so every use of it sits inside try / catch.
function loadItems() {
  try {
    var saved = localStorage.getItem(STORAGE_KEY);
    var items = saved ? JSON.parse(saved) : [];
    return Array.isArray(items) ? items : [];
  } catch (error) {
    return [];
  }
}

function saveItems() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(items));
  } catch (error) {
    // Remembering is a bonus. The list still works without it.
  }
}

// Each item looks like { text: "Buy milk", done: false }.
var items = loadItems();

// render draws the whole list again from the items.
function render() {
  list.innerHTML = "";
  hint.hidden = items.length > 0;

  items.forEach(function (item, index) {
    var row = document.createElement("li");
    var label = document.createElement("label");

    var box = document.createElement("input");
    box.type = "checkbox";
    box.checked = item.done === true;
    // addEventListener says: when this happens, run this function.
    box.addEventListener("change", function () {
      items[index].done = box.checked;
      saveItems();
      render();
    });

    var words = document.createElement("span");
    // textContent shows plain text, so nothing typed can change the page.
    words.textContent = String(item.text);
    if (item.done === true) {
      words.className = "done";
    }

    var remove = document.createElement("button");
    remove.type = "button";
    remove.textContent = "Remove";
    remove.addEventListener("click", function () {
      items.splice(index, 1);
      saveItems();
      render();
    });

    label.appendChild(box);
    label.appendChild(words);
    row.appendChild(label);
    row.appendChild(remove);
    list.appendChild(row);
  });
}

// "submit" happens when the Add button is tapped.
form.addEventListener("submit", function (event) {
  // Stops the page from reloading.
  event.preventDefault();
  var text = input.value.trim();
  if (text === "") {
    return;
  }
  items.push({ text: text, done: false });
  input.value = "";
  saveItems();
  render();
});

render();
"""

private const val NOTES_MD = """# Notes in Markdown

Markdown is a simple way to write formatted text. This page is a file called **README.md**.
Change the words below, then tap Run again to see the result.

## What you can write

- **Bold** text has two stars on each side.
- *Italic* text has one star on each side.
- A list starts every line with a dash.

## A link

Here is a link: [Learn Markdown](https://commonmark.org/help/)

## A code block

Put three backticks above and below your code:

```
print("Hello")
```
"""
