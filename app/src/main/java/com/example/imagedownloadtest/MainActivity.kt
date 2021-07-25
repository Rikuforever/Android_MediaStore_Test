package com.example.imagedownloadtest

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import java.io.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE = 1104
    }


    private lateinit var inputImageView: ImageView
    private lateinit var outputImageView: ImageView
    private lateinit var inputUriTextView: TextView
    private lateinit var outputUriTextView: TextView
    private lateinit var saveButton: Button
    private lateinit var openButton: Button
    private lateinit var loadButton: Button
    private lateinit var deleteButton: Button

    private var currentInputUri: Uri? = null
    private var currentOutputUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputImageView = findViewById(R.id.image_input)
        outputImageView = findViewById(R.id.image_output)
        inputUriTextView = findViewById(R.id.text_input)
        outputUriTextView = findViewById(R.id.text_output)
        saveButton = findViewById(R.id.button_save)
        openButton = findViewById(R.id.button_open)
        loadButton = findViewById(R.id.button_load)
        deleteButton = findViewById(R.id.button_delete)

        initSaveButton()
        initOpenButton()
        initLoadButton()
        initDeleteButton()
    }

    override fun onResume() {
        super.onResume()

        if (intent.action == Intent.ACTION_SEND) {
            if (intent.type?.startsWith("image/") == true) {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { inputUri ->
                    inputUriTextView.text = inputUri.toString()
                    loadImage(inputUri)

                    currentInputUri = inputUri
                }
            }
        }
    }


    private fun initSaveButton() {
        saveButton.setOnClickListener {
            currentInputUri?.let { downloadImage(it) }
        }
    }

    private fun initOpenButton() {
        openButton.setOnClickListener {
            val outputUri = currentOutputUri ?: return@setOnClickListener
            val viewIntent = Intent(Intent.ACTION_VIEW).setData(outputUri)
            startActivity(viewIntent)
        }
    }

    private fun initLoadButton() {
        loadButton.setOnClickListener {
            val outputUri = currentOutputUri ?: return@setOnClickListener
            Glide.with(outputImageView)
                .load(outputUri)
                .into(outputImageView)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initDeleteButton() {
        deleteButton.setOnClickListener {
            val outputUri = currentOutputUri ?: return@setOnClickListener

            // source : https://codechacha.com/ko/android-mediastore-remove-media-files/
            contentResolver.delete(outputUri, null, null)
            outputUriTextView.text = "Image deleted..."
        }
    }

    private fun loadImage(imageUri: Uri) {
        Glide.with(inputImageView)
            .load(imageUri)
            .into(inputImageView)
    }

    // source : https://www.programmersought.com/article/32231162989/
    private fun downloadImage(imageUri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            downloadByMediaStore(imageUri)
        } else {
            downloadByExternalStoragePath(imageUri)
        }
    }


    // region Download By Media Store (Q 이상)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun downloadByMediaStore(imageUri: Uri) {
        val imageName = imageUri.lastPathSegment ?: return

        val newGalleryUri = createNewImageUri(imageName) ?: return
        val isCopySuccess = copyContent(imageUri, newGalleryUri)

        if (isCopySuccess) {
            registerNewImageUri(newGalleryUri)
        }
    }

    // https://codechacha.com/ko/android-mediastore-insert-media-files/
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createNewImageUri(displayName: String): Uri? {
        val currentTime = System.currentTimeMillis()
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, currentTime)
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.DATE_ADDED, currentTime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/bookmark")
            put(MediaStore.Images.Media.MIME_TYPE, "image/*") // TODO set mime type as file extensions
        }


        return try {
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // endregion Download By Media Store


    // region Download By ExternalStorage (P 이하)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return // below logic is only for P OS

        if (requestCode == REQUEST_CODE) {
            permissions.withIndex().forEach { indexedValue ->
                val permission = indexedValue.value
                val grantResult = grantResults[indexedValue.index]

                if (permission != Manifest.permission.WRITE_EXTERNAL_STORAGE) return@forEach

                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    currentInputUri?.let { downloadByExternalStoragePath(it) }
                } else {
                    Toast.makeText(this, "Don't have permission", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    private fun downloadByExternalStoragePath(imageUri: Uri) {
        val imageName = imageUri.lastPathSegment ?: return

        val hasWritePermission = checkWritePermission()
        if (!hasWritePermission) {
            grantWritePermissions()
            return
        }

        Glide.with(this)
            .downloadOnly()
            .load(imageUri)
            .into(object : CustomTarget<File>() {
                override fun onResourceReady(resource: File, transition: Transition<in File>?) {
                    val copiedFile = saveFileToExternalStorage(resource, imageName) ?: return

                    logDebug("Copied File : $copiedFile")

                    val newGalleryUri = addFileToMediaStore(copiedFile, imageName) ?: return
                    registerNewImageUri(newGalleryUri)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    TODO("Not yet implemented")
                }
            })
    }

    // source : https://wowon.tistory.com/148
    private fun checkWritePermission(): Boolean {
        return checkCallingOrSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun grantWritePermissions() {
        requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_CODE)
    }

    private fun saveFileToExternalStorage(sourceFile: File, fileName: String): File? {
        try {
            val externalPath = getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.absolutePath ?: return null
            val bookmarkDir = File(externalPath, "bookmark")
            if (!bookmarkDir.exists()) {
                bookmarkDir.mkdirs()
            }

            val targetFile = File(bookmarkDir, fileName)
            val isCopySuccess = copy(sourceFile, targetFile)

            return if (isCopySuccess) {
                Toast.makeText(this, "Download Success", Toast.LENGTH_SHORT)?.show()
                targetFile
            } else {
                Toast.makeText(this, "Download Fail", Toast.LENGTH_SHORT)?.show()
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()

            Toast.makeText(this, "Download Fail", Toast.LENGTH_SHORT)?.show()
            return null
        }
    }

    private fun addFileToMediaStore(imageFile: File, displayName: String): Uri? {
        val currentTime = System.currentTimeMillis()
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.DATE_ADDED, currentTime)
            put(MediaStore.Images.Media.MIME_TYPE, "image/*")
            put(MediaStore.Images.Media.DATA, imageFile.absolutePath)
        }

        return contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    }

    // endregion Download By External Storage

    private fun registerNewImageUri(newImageUri: Uri) {
        currentOutputUri = newImageUri
        outputUriTextView.text = newImageUri.toString()
    }


    // region File Utils

    /**
     * Copy files
     *
     * @param source input file
     * @param target output file
     * @return file copied successfully
     */
    private fun copy(source: File?, target: File?): Boolean {
        var status = true
        var fileInputStream: FileInputStream? = null
        var fileOutputStream: FileOutputStream? = null
        try {
            fileInputStream = FileInputStream(source)
            fileOutputStream = FileOutputStream(target)
            val buffer = ByteArray(1024)
            while (fileInputStream.read(buffer) > 0) {
                fileOutputStream.write(buffer)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            status = false
        } finally {
            try {
                fileInputStream?.close()
                fileOutputStream?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return status
    }

    /**
     * Copy files
     *
     * @param sourceUri input uri
     * @param targetUri output uri
     * @return file copied successfully
     */
    private fun copyContent(sourceUri: Uri, targetUri: Uri): Boolean {
        var status = true
        var fileInputStream: InputStream? = null
        var fileOutputStream: OutputStream? = null
        try {
            fileInputStream = contentResolver.openInputStream(sourceUri) ?: throw Exception("Null Input Stream")
            fileOutputStream = contentResolver.openOutputStream(targetUri) ?: throw Exception("Null Output Stream")
            val buffer = ByteArray(1024)
            while (fileInputStream.read(buffer) > 0) {
                fileOutputStream.write(buffer)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            status = false
        } finally {
            try {
                fileInputStream?.close()
                fileOutputStream?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return status
    }

    // endregion File Utils

    private fun logDebug(message: String) {
        Log.d("HJ", message)
    }

    // thumbnail : https://stackoverflow.com/questions/28243330/add-image-to-media-gallery-android
}