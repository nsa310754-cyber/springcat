"""``python -m fixapk`` -> command line; ``python -m fixapk --gui`` -> window."""

import sys

if "--gui" in sys.argv[1:]:
    from .gui import main as gui_main

    raise SystemExit(gui_main())
else:
    from .cli import main

    raise SystemExit(main())
