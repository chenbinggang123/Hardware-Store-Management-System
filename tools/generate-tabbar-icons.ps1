param(
  [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\miniprogram\assets\tabbar')
)

Add-Type -AssemblyName System.Drawing

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($resolvedOutput) | Out-Null

function New-Pen([string]$hex) {
  $pen = [System.Drawing.Pen]::new([System.Drawing.ColorTranslator]::FromHtml($hex), 5.2)
  $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
  $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
  $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
  return $pen
}

function Add-RoundedRectanglePath($path, [float]$x, [float]$y, [float]$width, [float]$height, [float]$radius) {
  $diameter = $radius * 2
  $path.AddArc($x, $y, $diameter, $diameter, 180, 90)
  $path.AddArc($x + $width - $diameter, $y, $diameter, $diameter, 270, 90)
  $path.AddArc($x + $width - $diameter, $y + $height - $diameter, $diameter, $diameter, 0, 90)
  $path.AddArc($x, $y + $height - $diameter, $diameter, $diameter, 90, 90)
  $path.CloseFigure()
}

function Draw-Icon([string]$name, [string]$color, [string]$fileName) {
  $bitmap = [System.Drawing.Bitmap]::new(81, 81, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
  $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
  $graphics.Clear([System.Drawing.Color]::Transparent)
  $pen = New-Pen $color

  switch ($name) {
    'home' {
      $graphics.DrawLines($pen, [System.Drawing.PointF[]]@(
        [System.Drawing.PointF]::new(16, 38),
        [System.Drawing.PointF]::new(40.5, 17),
        [System.Drawing.PointF]::new(65, 38)
      ))
      $graphics.DrawLine($pen, 22, 35, 22, 64)
      $graphics.DrawLine($pen, 59, 35, 59, 64)
      $graphics.DrawLine($pen, 22, 64, 59, 64)
      $graphics.DrawLine($pen, 34, 64, 34, 49)
      $graphics.DrawLine($pen, 34, 49, 47, 49)
      $graphics.DrawLine($pen, 47, 49, 47, 64)
    }
    'products' {
      $graphics.DrawRectangle($pen, 14, 18, 23, 22)
      $graphics.DrawRectangle($pen, 44, 18, 23, 22)
      $graphics.DrawRectangle($pen, 29, 46, 23, 22)
      $graphics.DrawLine($pen, 14, 29, 37, 29)
      $graphics.DrawLine($pen, 44, 29, 67, 29)
      $graphics.DrawLine($pen, 29, 57, 52, 57)
    }
    'orders' {
      $path = [System.Drawing.Drawing2D.GraphicsPath]::new()
      Add-RoundedRectanglePath $path 21 13 39 56 6
      $graphics.DrawPath($pen, $path)
      $graphics.DrawLine($pen, 30, 29, 51, 29)
      $graphics.DrawLine($pen, 30, 41, 51, 41)
      $graphics.DrawLine($pen, 30, 53, 45, 53)
      $path.Dispose()
    }
    'inventory' {
      $graphics.DrawLines($pen, [System.Drawing.PointF[]]@(
        [System.Drawing.PointF]::new(14, 31),
        [System.Drawing.PointF]::new(40.5, 15),
        [System.Drawing.PointF]::new(67, 31)
      ))
      $graphics.DrawRectangle($pen, 18, 30, 45, 36)
      $graphics.DrawLine($pen, 29, 30, 29, 66)
      $graphics.DrawLine($pen, 52, 30, 52, 66)
      $graphics.DrawLine($pen, 18, 47, 63, 47)
    }
    'agent' {
      $path = [System.Drawing.Drawing2D.GraphicsPath]::new()
      Add-RoundedRectanglePath $path 13 16 55 46 12
      $graphics.DrawPath($pen, $path)
      $graphics.DrawLines($pen, [System.Drawing.PointF[]]@(
        [System.Drawing.PointF]::new(28, 61),
        [System.Drawing.PointF]::new(22, 69),
        [System.Drawing.PointF]::new(40, 62)
      ))
      $graphics.DrawLine($pen, 41, 27, 41, 49)
      $graphics.DrawLine($pen, 30, 38, 52, 38)
      $graphics.DrawLine($pen, 34, 31, 48, 45)
      $graphics.DrawLine($pen, 48, 31, 34, 45)
      $path.Dispose()
    }
  }

  $destination = Join-Path $resolvedOutput $fileName
  $bitmap.Save($destination, [System.Drawing.Imaging.ImageFormat]::Png)
  $pen.Dispose()
  $graphics.Dispose()
  $bitmap.Dispose()
}

$icons = @('home', 'products', 'orders', 'inventory', 'agent')
foreach ($icon in $icons) {
  Draw-Icon $icon '#66716D' "$icon.png"
  Draw-Icon $icon '#2865D7' "$icon-active.png"
}

Write-Output "Generated tab bar icons in $resolvedOutput"
