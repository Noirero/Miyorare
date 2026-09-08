from pathlib import Path

path = Path('app/src/main/res/layout/fragment_favourites_container.xml')
text = path.read_text()
old_prefix = '''<?xml version="1.0" encoding="utf-8"?>
<FrameLayout
\txmlns:android="http://schemas.android.com/apk/res/android"
\txmlns:app="http://schemas.android.com/apk/res-auto"
\txmlns:tools="http://schemas.android.com/tools"
\tandroid:layout_width="match_parent"
\tandroid:layout_height="match_parent">

\t<LinearLayout
\t\tandroid:id="@+id/layout_content"
\t\tandroid:layout_width="match_parent"
\t\tandroid:layout_height="match_parent"
\t\tandroid:orientation="vertical">
'''
new_prefix = '''<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
\txmlns:android="http://schemas.android.com/apk/res/android"
\txmlns:app="http://schemas.android.com/apk/res-auto"
\txmlns:tools="http://schemas.android.com/tools"
\tandroid:id="@+id/layout_content"
\tandroid:layout_width="match_parent"
\tandroid:layout_height="match_parent"
\tandroid:orientation="vertical">
'''
old_suffix = '''
\t</LinearLayout>

</FrameLayout>
'''
new_suffix = '''
</LinearLayout>
'''
if old_prefix not in text:
    raise SystemExit('expected FrameLayout/LinearLayout prefix not found')
if old_suffix not in text:
    raise SystemExit('expected FrameLayout suffix not found')
text = text.replace(old_prefix, new_prefix, 1)
text = text.replace(old_suffix, new_suffix, 1)
path.write_text(text)
print('Removed redundant favourites container parent')
