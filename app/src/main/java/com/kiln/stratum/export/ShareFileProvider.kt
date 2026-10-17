package com.kiln.stratum.export

import androidx.core.content.FileProvider
import com.kiln.stratum.R

/**
 * 공유할 파일(앱 임시 폴더 cache/shared)을 다른 앱이 읽을 수 있는 주소로 내준다. 폴더는 `res/xml/share_paths.xml`이다.
 * androidx 문서가 FileProvider를 그대로 매니페스트에 쓰지 말고 이렇게 하위 클래스로 쓰라고 한다(소스 확인).
 */
class ShareFileProvider : FileProvider(R.xml.share_paths)
