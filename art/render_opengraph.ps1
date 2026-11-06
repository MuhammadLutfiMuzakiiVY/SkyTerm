Add-Type -AssemblyName System.Drawing

$bmp = New-Object System.Drawing.Bitmap 1280, 640, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$g.Clear([System.Drawing.Color]::FromArgb(255, 10, 15, 29))

# Outer Gradient Background
$bgBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush (New-Object System.Drawing.Point 0, 0), (New-Object System.Drawing.Point 1280, 640), ([System.Drawing.Color]::FromArgb(255, 15, 23, 42)), ([System.Drawing.Color]::FromArgb(255, 3, 7, 18))
$g.FillRectangle($bgBrush, 0, 0, 1280, 640)

# Subtle Background Grid
$gridPen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(25, 56, 189, 248)), 1
for ($gx = 0; $gx -le 1280; $gx += 40) { $g.DrawLine($gridPen, $gx, 0, $gx, 640) }
for ($gy = 0; $gy -le 640; $gy += 40) { $g.DrawLine($gridPen, 0, $gy, 1280, $gy) }

# Logo Base Squircle (on left side: x=140, y=120, w=400, h=400)
$lx = 140
$ly = 120
$lw = 400
$lh = 400
$r = 80
$d = $r * 2

$path = New-Object System.Drawing.Drawing2D.GraphicsPath
$path.AddArc($lx, $ly, $d, $d, 180, 90)
$path.AddArc($lx + $lw - $d, $ly, $d, $d, 270, 90)
$path.AddArc($lx + $lw - $d, $ly + $lh - $d, $d, $d, 0, 90)
$path.AddArc($lx, $ly + $lh - $d, $d, $d, 90, 90)
$path.CloseFigure()

$logoBg = New-Object System.Drawing.Drawing2D.LinearGradientBrush (New-Object System.Drawing.Point $lx, $ly), (New-Object System.Drawing.Point ($lx + $lw), ($ly + $lh)), ([System.Drawing.Color]::FromArgb(255, 15, 23, 42)), ([System.Drawing.Color]::FromArgb(255, 2, 6, 23))
$g.FillPath($logoBg, $path)

$logoBorder = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(220, 56, 189, 248)), 4
$g.DrawPath($logoBorder, $path)

# Top Bar Divider & Dots
$divPen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255, 30, 41, 59)), 2
$g.DrawLine($divPen, $lx, $ly + 70, $lx + $lw, $ly + 70)

$g.FillEllipse((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 239, 68, 68))), $lx + 35, $ly + 28, 16, 16)
$g.FillEllipse((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 245, 158, 11))), $lx + 65, $ly + 28, 16, 16)
$g.FillEllipse((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 16, 185, 129))), $lx + 95, $ly + 28, 16, 16)

# Chevron (>) inside logo
$chevPoints = @(
    (New-Object System.Drawing.Point ($lx + 80), ($ly + 130)),
    (New-Object System.Drawing.Point ($lx + 190), ($ly + 225)),
    (New-Object System.Drawing.Point ($lx + 80), ($ly + 320)),
    (New-Object System.Drawing.Point ($lx + 130), ($ly + 320)),
    (New-Object System.Drawing.Point ($lx + 240), ($ly + 225)),
    (New-Object System.Drawing.Point ($lx + 130), ($ly + 130))
)
$chevPath = New-Object System.Drawing.Drawing2D.GraphicsPath
$chevPath.AddPolygon($chevPoints)
$skyBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush (New-Object System.Drawing.Point $lx, $ly), (New-Object System.Drawing.Point ($lx + $lw), ($ly + $lh)), ([System.Drawing.Color]::FromArgb(255, 56, 189, 248)), ([System.Drawing.Color]::FromArgb(255, 0, 229, 255))
$g.FillPath($skyBrush, $chevPath)

# Cursor (_)
$cursorBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 248, 250, 252))
$g.FillRectangle($cursorBrush, $lx + 260, $ly + 285, 65, 35)

# Text on right side
$titleFont = New-Object System.Drawing.Font 'Segoe UI', 50, ([System.Drawing.FontStyle]::Bold)
$titleBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 255, 255, 255))
$g.DrawString('SkyTerm', $titleFont, $titleBrush, 600, 160)

$subFont = New-Object System.Drawing.Font 'Segoe UI', 20, ([System.Drawing.FontStyle]::Regular)
$subBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 56, 189, 248))
$g.DrawString('Android Linux Terminal & Environment', $subFont, $subBrush, 605, 260)

$descFont = New-Object System.Drawing.Font 'Segoe UI', 15, ([System.Drawing.FontStyle]::Regular)
$descBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 148, 163, 184))
$g.DrawString('Universal Architecture | Optimized Native Linux Runtime', $descFont, $descBrush, 605, 315)

# Badge: ARM64 • ARMv7 • x86 • x86_64
$badgeBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 30, 41, 59))
$g.FillRectangle($badgeBrush, 605, 380, 520, 44)
$badgeBorder = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(100, 56, 189, 248)), 1
$g.DrawRectangle($badgeBorder, 605, 380, 520, 44)
$badgeFont = New-Object System.Drawing.Font 'Segoe UI', 12, ([System.Drawing.FontStyle]::Bold)
$badgeTextBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 0, 229, 255))
$g.DrawString('ARM64-V8A   |   ARMEABI-V7A   |   X86   |   X86_64', $badgeFont, $badgeTextBrush, 645, 392)

# Save
$outPath = 'c:\Users\muham\OneDrive\Documents\MOBILE\SkyTerm\art\opengraph-image.png'
$bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
$bmp.Dispose()
Write-Output 'Rendered opengraph-image.png successfully.'
