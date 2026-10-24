package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.selection.MaskTile

/** 대상 타일에 놓을 원본 조각 하나. [part]의 원본 타일이 [tile]이다. */
data class TilePiece(val tile: Tile, val part: TilePart)

/** 대상 마스크 타일에 놓을 원본 조각 하나. [part]의 원본 마스크 타일이 [tile]이다. */
data class MaskPiece(val tile: MaskTile, val part: TilePart)

/**
 * 원본 조각들을 옮겨 놓아 타일 한 장을 만든다. 픽셀 값은 그대로 두고 자리만 옮긴다.
 *
 * 순수 Kotlin 엔진([RasterTransform])은 무엇을 어디로 옮길지만 정하고, 실제 복사는 이것을 구현한 플랫폼 코드가 한다.
 */
interface TileComposer {

    /**
     * 캔버스 안쪽이 [width]×[height]인 타일을 만든다. [pieces]를 [orientation] 방향으로 옮겨 놓는다([TilePart.sourcePixel]과 같은 방법).
     * 조각이 덮지 않은 곳과 캔버스 밖 부분은 투명이다. 메모리가 모자라면 예외를 던진다.
     */
    fun compose(width: Int, height: Int, pieces: List<TilePiece>, orientation: Orientation): Tile

    /**
     * [argb] 한 가지 색만 담은 [width]×[height] 타일(한 가지 색 타일을 크기가 다른 자리로 옮길 때, 결정 기록 '캔버스 밖 픽셀').
     * 메모리가 모자라면 예외를 던진다.
     */
    fun solid(argb: Int, width: Int, height: Int): Tile

    /**
     * 캔버스 안쪽이 [width]×[height]인 마스크 타일(레이어 마스크, 결정 기록 '피부 보정'의 1c). [pieces]를 같은 방법으로 옮겨 놓고,
     * 조각이 덮지 않은 곳은 0(가림)이다. 다 0이면 null, 다 255면 [MaskTile.FULL]이다. 메모리가 모자라면 예외를 던진다.
     */
    fun composeMask(width: Int, height: Int, pieces: List<MaskPiece>, orientation: Orientation): MaskTile?
}
