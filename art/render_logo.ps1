Add-Type -AssemblyName System.Drawing

$bmp = New-Object System.Drawing.Bitmap 512, 512, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$g.Clear([System.Drawing.Color]::Transparent)

# Draw Rounded Squircle Base
$x = 24
$y = 24
$w = 464
$h = 464
$r = 96
$d = $r * 2

$path = New-Object System.Drawing.Drawing2D.GraphicsPath
$path.AddArc($x, $y, $d, $d, 180, 90)
$path.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
$path.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
$path.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
$path.CloseFigure()

# Background Gradient
$bgBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush (New-Object System.Drawing.Point 24, 24), (New-Object System.Drawing.Point 488, 488), ([System.Drawing.Color]::FromArgb(255, 15, 23, 42)), ([System.Drawing.Color]::FromArgb(255, 3, 7, 18))
$g.FillPath($bgBrush, $path)

# Border Gradient Pen
$borderPen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(180, 56, 189, 248)), 4
$g.DrawPath($borderPen, $path)

# Top Bar Divider
$divPen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255, 30, 41, 59)), 2
$g.DrawLine($divPen, 24, 120, 488, 120)

# Window Dots
$dotRed = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 239, 68, 68))
$dotYellow = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 245, 158, 11))
$dotGreen = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 16, 185, 129))
$g.FillEllipse($dotRed, 70, 62, 20, 20)
$g.FillEllipse($dotYellow, 104, 62, 20, 20)
$g.FillEllipse($dotGreen, 138, 62, 20, 20)

# Top Bar Title Text
$font = New-Object System.Drawing.Font 'Segoe UI', 12, ([System.Drawing.FontStyle]::Bold)
$textBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 100, 116, 139))
$sf = New-Object System.Drawing.StringFormat
$sf.Alignment = [System.Drawing.StringAlignment]::Center
$g.DrawString('S K Y T E R M', $font, $textBrush, 260, 64, $sf)

# Chevron Graphic (>)
$chevPoints = @(
    (New-Object System.Drawing.Point 120, 180),
    (New-Object System.Drawing.Point 250, 290),
    (New-Object System.Drawing.Point 120, 400),
    (New-Object System.Drawing.Point 175, 400),
    (New-Object System.Drawing.Point 305, 290),
    (New-Object System.Drawing.Point 175, 180)
)
$chevPath = New-Object System.Drawing.Drawing2D.GraphicsPath
$chevPath.AddPolygon($chevPoints)
$skyBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush (New-Object System.Drawing.Point 120, 180), (New-Object System.Drawing.Point 305, 400), ([System.Drawing.Color]::FromArgb(255, 56, 189, 248)), ([System.Drawing.Color]::FromArgb(255, 0, 229, 255))
$g.FillPath($skyBrush, $chevPath)

# Terminal Cursor Bar (_)
$cursorBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 248, 250, 252))
$cursorRect = New-Object System.Drawing.Rectangle 330, 360, 80, 40
$g.FillRectangle($cursorBrush, $cursorRect)

# Cyan Star / Spark Accent
$starBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(220, 56, 189, 248))
$starPoints = @(
    (New-Object System.Drawing.Point 350, 210),
    (New-Object System.Drawing.Point 365, 240),
    (New-Object System.Drawing.Point 395, 245),
    (New-Object System.Drawing.Point 370, 265),
    (New-Object System.Drawing.Point 375, 295),
    (New-Object System.Drawing.Point 350, 278),
    (New-Object System.Drawing.Point 325, 295),
    (New-Object System.Drawing.Point 330, 265),
    (New-Object System.Drawing.Point 305, 245),
    (New-Object System.Drawing.Point 335, 240)
)
$starPath = New-Object System.Drawing.Drawing2D.GraphicsPath
$starPath.AddPolygon($starPoints)
$g.FillPath($starBrush, $starPath)

# Save high-res PNG
$outPath = 'c:\Users\muham\OneDrive\Documents\MOBILE\SkyTerm\art\skyterm-logo.png'
$bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
$bmp.Dispose()
Write-Output 'Rendered skyterm-logo.png successfully.'
