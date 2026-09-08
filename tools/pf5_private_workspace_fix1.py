from pathlib import Path
p = Path('app/src/main/res/layout/fragment_private_workspace.xml')
text = p.read_text()
text = text.replace('        app:itemActiveIndicatorStyle="@style/Widget.Material3.NavigationBarView.ActiveIndicator"\n', '')
p.write_text(text)
print('Removed unavailable NavigationBar active-indicator style')
